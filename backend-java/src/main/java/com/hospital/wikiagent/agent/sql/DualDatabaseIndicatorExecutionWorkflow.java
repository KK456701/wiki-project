package com.hospital.wikiagent.agent.sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.diagnosis.DiagnosisReportRepository;
import com.hospital.wikiagent.agent.extraction.ExtractionProperties;
import com.hospital.wikiagent.agent.extraction.ExtractionRequest;
import com.hospital.wikiagent.agent.extraction.ExtractionResult;
import com.hospital.wikiagent.agent.extraction.HospitalExecutionLock;
import com.hospital.wikiagent.agent.extraction.SourceExtractionGateway;
import com.hospital.wikiagent.agent.extraction.SourceExtractionLease;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.planning.StatPeriodPolicy;
import com.hospital.wikiagent.agent.runtime.AgentRunState.WorkflowProgress;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.dbhub.DbHubMcpException;

/**
 * 在源数据抽取完成后，使用本轮真实库快照执行指标计算。
 *
 * <p>普通计算固定执行“抽取、真实库概览、释放快照锁”，不再重复查询业务库。
 * 诊断分支暂保留既有双库核对，且不把患者行写入 Trace。</p>
 */
@Component
public class DualDatabaseIndicatorExecutionWorkflow {
    public static final String VERSION = "profile-snapshot-indicator-workflow-v2";
    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExtractionProperties extractionProperties;
    private final SourceExtractionGateway extractionGateway;
    private final HospitalExecutionLock executionLock;
    private final IndicatorDatabaseQueryClient databaseQuery;
    private final SqlParameterBinder binder;
    private final ReadOnlySqlValidator validator;
    private final SqlObjectRepository objects;
    private final DiagnosisReportRepository diagnosisReports;

    public DualDatabaseIndicatorExecutionWorkflow(
            ExtractionProperties extractionProperties,
            SourceExtractionGateway extractionGateway,
            HospitalExecutionLock executionLock,
            IndicatorDatabaseQueryClient databaseQuery,
            SqlParameterBinder binder,
            ReadOnlySqlValidator validator,
            SqlObjectRepository objects,
            DiagnosisReportRepository diagnosisReports) {
        this.extractionProperties = extractionProperties;
        this.extractionGateway = extractionGateway;
        this.executionLock = executionLock;
        this.databaseQuery = databaseQuery;
        this.binder = binder;
        this.validator = validator;
        this.objects = objects;
        this.diagnosisReports = diagnosisReports;
    }

    /**
     * 双库 Workflow 是普通指标计算的固定执行路径，不再由抽取开关控制。
     */
    public boolean enabled() {
        return true;
    }

    /**
     * 抽取接口是否是本轮真实库计算的强制前置步骤。
     */
    public boolean extractionRequired() {
        return extractionProperties.required();
    }

    public ToolResult execute(
            PreparedSqlObject sql,
            Map<String, Object> rule,
            String executableOverview,
            Map<String, Object> boundParameters,
            ToolExecutionContext context) {
        boolean explicitDiagnosis =
                PlanIntent.INDICATOR_DIAGNOSIS.value().equals(
                        context.runState().lastIntent());
        boolean realOnlyCalculation = !explicitDiagnosis;
        if (realOnlyCalculation && !extractionRequired()) {
            return ToolResult.failure(
                    "unavailable", "SOURCE_EXTRACTION_REQUIRED",
                    "指标计算必须先刷新真实库快照，请以 required 模式启用受控抽取。", false);
        }
        if (extractionRequired() && !extractionGateway.available()) {
            return ToolResult.failure(
                    "unavailable", "EXTRACTION_GATEWAY_UNAVAILABLE",
                    "源数据抽取接口尚未接入，不能执行指标计算。", false);
        }

        LocalDateTime statStart;
        LocalDateTime statEnd;
        try {
            statStart = parseTime(sql.statStart());
            statEnd = parseTime(sql.statEnd());
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    "validation_failed", "STAT_PERIOD_INVALID",
                    "统计时间格式无效。", false);
        }
        StatPeriodPolicy.Validation period =
                StatPeriodPolicy.validate(statStart, statEnd);
        report(context, "dual_period_validation", "校验统计范围",
                period.ok() ? "success" : "failed", 0,
                Map.of(
                        "stat_start", sql.statStart(),
                        "stat_end", sql.statEnd(),
                        "code", period.code()));
        if (!period.ok()) {
            return ToolResult.failure(
                    "validation_failed", period.code(), period.message(), false);
        }

        Map<String, Object> contract = objectMap(rule.get("dual_database_contract"));
        boolean fullSchemaContract = schemaCompatible(contract);
        boolean overviewStaticRuntime =
                Boolean.TRUE.equals(rule.get("overview_runtime_eligible"));
        if (realOnlyCalculation && !overviewStaticRuntime) {
            return ToolResult.failure(
                    "validation_failed", "REAL_DATABASE_SCHEMA_INCOMPATIBLE",
                    "当前 Profile 尚未通过真实库概览执行门禁，未执行抽取或数据库查询。",
                    false);
        }
        if (!realOnlyCalculation && !fullSchemaContract && !overviewStaticRuntime) {
            return ToolResult.failure(
                    "validation_failed", "DUAL_DATABASE_SCHEMA_INCOMPATIBLE",
                    "当前 Profile 尚未确认业务库与真实库具备同构查询对象，未执行抽取或数据库查询。",
                    false);
        }

        String sourceSql = text(rule.get("source_extract_sql"));
        boolean sourceSqlRequired = "EVENT".equalsIgnoreCase(text(
                objectMap(rule.get("extraction_contract")).get("route")));
        if (extractionRequired() && sourceSqlRequired
                && (sourceSql.isBlank() || !validator.validateReadOnly(sourceSql).ok())) {
            return ToolResult.failure(
                    "validation_failed", "SOURCE_EXTRACT_SQL_UNAVAILABLE",
                    "当前 Profile 的源数据 SQL 尚未通过可执行校验。", false);
        }
        if (!validator.validateReadOnly(sql.sqlText()).ok()) {
            return ToolResult.failure(
                    "validation_failed", "SQL_REVALIDATION_FAILED",
                    "概览 SQL 在真实库执行前未通过只读安全校验。", false);
        }
        Map<String, Object> extractionPreparation = new LinkedHashMap<>();
        extractionPreparation.put(
                "mode", extractionRequired() ? "required" : "disabled");
        extractionPreparation.put("release_id", text(rule.get("knowledge_release_id")));
        extractionPreparation.put("rule_id", sql.ruleId());
        extractionPreparation.put("profile_id", text(rule.get("profile_id")));
        if (!sourceSql.isBlank()) {
            extractionPreparation.put("source_sql_sha256", sha256(sourceSql));
        }
        report(context, "source_extraction_prepare", "准备源数据抽取",
                extractionRequired() ? "success" : "skipped", 0,
                extractionPreparation);

        try (HospitalExecutionLock.Lease ignored =
                     executionLock.acquire(context.agentContext().hospitalId());
             SourceExtractionLease extractionLease = extractionRequired()
                     ? prepareExtraction(
                             sql, rule, sourceSql, boundParameters, context)
                     : SourceExtractionLease.completed(skippedExtraction(context))) {
            ExtractionResult extraction = extractionLease.result();
            if (!extraction.allowsDualExecution()) {
                return ToolResult.failure(
                        "error",
                        blank(extraction.errorCode(), "SOURCE_EXTRACTION_FAILED"),
                        blank(extraction.message(), "源数据抽取失败，未执行真实库计算。"),
                        false);
            }
            if (realOnlyCalculation) {
                return executeRealOnlyCalculation(
                        sql, rule, executableOverview, contract, extraction, context);
            }

            DatabaseResult business;
            try {
                business = executeOverview(
                        DatabaseRole.BUSINESS, sql, executableOverview, contract, context);
            } catch (OverviewResultContractException exception) {
                return ToolResult.failure(
                        "validation_failed", "OVERVIEW_RESULT_CONTRACT_INVALID",
                        "概览结果列与已发布映射不一致，请修正知识契约后重试。", false);
            } catch (RuntimeException exception) {
                return ToolResult.failure(
                        "error", "BUSINESS_DATABASE_OVERVIEW_FAILED",
                        "业务库概览计算失败，未形成双库比较结论。", false);
            }

            DatabaseResult real;
            try {
                real = executeOverview(
                        DatabaseRole.REAL, sql, executableOverview, contract, context);
            } catch (OverviewResultContractException exception) {
                return ToolResult.failure(
                        "validation_failed", "OVERVIEW_RESULT_CONTRACT_INVALID",
                        "概览结果列与已发布映射不一致，请修正知识契约后重试。", false);
            } catch (RuntimeException exception) {
                return ToolResult.failure(
                        "error", "REAL_DATABASE_OVERVIEW_FAILED",
                        "真实库概览计算失败，未形成双库比较结论。", false);
            }

            boolean resultMatched = business.matches(real);
            boolean targetConflict = targetConflict(business, real);
            boolean matched = resultMatched && !targetConflict;
            String comparisonRunId = id("RUN_COMPOSITE_");
            /*
             * 静态概览试算用两个数据库的实际执行来验证表、字段和结果列是否可用。
             * 只有完整的明细比较契约才能在不一致时继续执行科室/患者 SQL；否则保留
             * 已确认的概览差异，并明确返回契约缺失，不能猜测具体差异记录。
             */
            Map<String, Object> diagnosis = matched && !explicitDiagnosis
                    ? Map.of("status", "skipped", "reason", "overview_matched")
                    : fullSchemaContract
                            ? new LinkedHashMap<>(
                                    diagnoseDetails(rule, boundParameters, contract, context))
                            : new LinkedHashMap<>(Map.of(
                                    "status", "incomplete",
                                    "code", "DETAIL_COMPARISON_CONTRACT_MISSING",
                                    "reason", matched
                                            ? "用户要求继续核对明细，但科室或患者明细比较契约尚未验证。"
                                            : "双库概览不一致，但科室或患者明细比较契约尚未验证。"));
            String comparisonStatus = matched ? "matched" : "mismatched";
            String diagnosisReportId = "";
            Map<String, Object> mismatch = matched
                    ? Map.of()
                    : mismatchSummary(business, real, diagnosis);
            try {
                objects.saveDualRun(
                        comparisonRunId,
                        sql,
                        text(rule.get("profile_id")),
                        extraction.extractionId(),
                        business.runId(),
                        real.runId(),
                        comparisonStatus,
                        mismatch,
                        context.agentContext().userId());
            } catch (RuntimeException exception) {
                return ToolResult.failure(
                        "error", "DUAL_RUN_PERSIST_FAILED",
                        "双库运行摘要保存失败，未生成可引用的比较结论。", false);
            }
            if (!matched || explicitDiagnosis) {
                try {
                    diagnosisReportId = saveDiagnosisReport(
                            comparisonRunId, sql, rule, extraction, business, real,
                            diagnosis, explicitDiagnosis, matched, context);
                } catch (RuntimeException exception) {
                    return ToolResult.failure(
                            "error", "DUAL_DIAGNOSIS_REPORT_PERSIST_FAILED",
                            "双库结果不一致，但诊断报告保存失败，未生成不完整结论。", false);
                }
                diagnosis.put("report_id", diagnosisReportId);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run_id", comparisonRunId);
            data.put("canonical_run_id", real.runId());
            data.put("sql_id", sql.sqlId());
            data.put("rule_id", sql.ruleId());
            data.put("profile_id", rule.get("profile_id"));
            data.put("stat_start", sql.statStart());
            data.put("stat_end", sql.statEnd());
            data.put("extraction_id", extraction.extractionId());
            data.put("extraction_status", extraction.status().name());
            data.put("data_freshness",
                    extraction.status() == ExtractionResult.Status.SKIPPED_DISABLED
                            ? "existing_snapshot_not_refreshed"
                            : "refreshed_by_current_run");
            data.put("comparison_status", comparisonStatus);
            data.put("result_comparison_status", resultMatched ? "matched" : "mismatched");
            data.put("target_comparison_status",
                    targetConflict ? "conflict" : "compatible");
            data.put("business_result", business.safeMap());
            data.put("real_result", real.safeMap());
            if (real.numerator() != null) {
                data.put("numerator_count", real.numerator());
            }
            if (real.denominator() != null) {
                data.put("denominator_count", real.denominator());
            }
            data.put("result_value", real.resultValue());
            putIfPresent(data, "component_left", real.componentLeft());
            putIfPresent(data, "component_right", real.componentRight());
            putIfPresent(data, "sample_count", real.sampleCount());
            Number resolvedTarget = resolvedTarget(business, real);
            if (resolvedTarget == null && !targetConflict) {
                resolvedTarget = profileTarget(contract);
            }
            putIfPresent(data, "target_value", resolvedTarget);
            if (targetConflict) {
                data.put("target_conflict", true);
                data.put("business_target_value", business.targetValue());
                data.put("real_target_value", real.targetValue());
            } else if (resolvedTarget != null) {
                String targetSource = targetSource(business, real);
                data.put("target_source",
                        targetSource.isBlank() ? "profile" : targetSource);
            }
            data.put("no_sample", real.denominator() != null
                    ? real.denominator() == 0
                    : real.resultValue() == null);
            data.put("dual_difference_diagnosis", diagnosis);
            if (!diagnosisReportId.isBlank()) {
                data.put("diagnosis_report_id", diagnosisReportId);
            }
            data.put("workflow_version", VERSION);
            context.runState().lastRunId(real.runId());
            report(context, "dual_comparison", "核对双库结果", "success", 0,
                    Map.of("comparison_status", comparisonStatus));
            report(context, "dual_diagnosis_conclusion", "生成诊断结论", "success", 0,
                    Map.of(
                            "comparison_status", comparisonStatus,
                            "diagnosis_status", diagnosis.getOrDefault("status", "skipped")));
            return ToolResult.success(
                    // 保留既有工具结果码，避免状态控制器、批处理和上传对比把双库
                    // 结果当成“尚未试运行”；双库语义由 comparison_status 区分。
                    "TRIAL_RUN_COMPLETED",
                    matched
                            ? explicitDiagnosis
                                    ? "业务库与真实库的指标结果一致，已按用户要求继续完成明细诊断。"
                                    : "业务库与真实库的指标结果一致。"
                            : "业务库与真实库结果不一致，已按可用契约执行受控诊断。",
                    data);
        }
    }

    private ToolResult executeRealOnlyCalculation(
            PreparedSqlObject sql,
            Map<String, Object> rule,
            String executableOverview,
            Map<String, Object> contract,
            ExtractionResult extraction,
            ToolExecutionContext context) {
        DatabaseResult real;
        try {
            real = executeOverview(
                    DatabaseRole.REAL, sql, executableOverview, contract, context);
        } catch (OverviewResultContractException exception) {
            return ToolResult.failure(
                    "validation_failed", "OVERVIEW_RESULT_CONTRACT_INVALID",
                    "真实库概览结果列与已发布映射不一致，请修正知识契约后重试。", false);
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    "error", "REAL_DATABASE_OVERVIEW_FAILED",
                    "真实库概览计算失败，未形成指标结果。", false);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", real.runId());
        data.put("canonical_run_id", real.runId());
        data.put("sql_id", sql.sqlId());
        data.put("rule_id", sql.ruleId());
        data.put("profile_id", rule.get("profile_id"));
        data.put("stat_start", sql.statStart());
        data.put("stat_end", sql.statEnd());
        data.put("extraction_id", extraction.extractionId());
        data.put("extraction_status", extraction.status().name());
        data.put("data_freshness", "refreshed_by_current_run");
        data.put("calculation_mode", "real_database_only");
        data.put("real_result", real.safeMap());
        if (real.numerator() != null) {
            data.put("numerator_count", real.numerator());
        }
        if (real.denominator() != null) {
            data.put("denominator_count", real.denominator());
        }
        data.put("result_value", real.resultValue());
        putIfPresent(data, "component_left", real.componentLeft());
        putIfPresent(data, "component_right", real.componentRight());
        putIfPresent(data, "sample_count", real.sampleCount());
        Number resolvedTarget = real.targetValue() != null
                ? real.targetValue() : profileTarget(contract);
        putIfPresent(data, "target_value", resolvedTarget);
        if (resolvedTarget != null) {
            data.put("target_source",
                    real.targetValue() != null ? "real" : "profile");
        }
        data.put("no_sample", real.denominator() != null
                ? real.denominator() == 0
                : real.resultValue() == null);
        data.put("workflow_version", VERSION);
        context.runState().lastRunId(real.runId());
        report(context, "real_calculation_complete", "完成真实库指标计算",
                "success", 0,
                Map.of(
                        "run_id", real.runId(),
                        "source_id", real.sourceId(),
                        "status", data.get("no_sample").equals(Boolean.TRUE)
                                ? "NO_SAMPLE" : "SUCCESS"));
        return ToolResult.success(
                "TRIAL_RUN_COMPLETED",
                "已刷新真实库快照并完成指标计算。",
                data);
    }

    /**
     * 抽取适配器尚未接入时生成显式的“已跳过”回执。
     *
     * <p>回执不包含抽取 ID、行数或快照，因此不会被误认为已经向真实库写入数据；
     * 它仅记录本轮为何直接进入两个数据库的只读计算。</p>
     */
    private ExtractionResult skippedExtraction(ToolExecutionContext context) {
        ExtractionResult result = new ExtractionResult(
                "",
                ExtractionResult.Status.SKIPPED_DISABLED,
                0, 0, 0, 0,
                java.time.Instant.now(),
                "", "",
                "",
                "抽取接口未启用，本轮跳过抽取并继续执行双库只读核对。");
        report(context, "source_data_extraction", "抽取数据到真实库",
                "skipped", 0,
                Map.of(
                        "status", "SKIPPED_DISABLED",
                        "reason", "extraction_mode_disabled",
                        "cache_reused", false));
        return result;
    }

    private String saveDiagnosisReport(
            String comparisonRunId,
            PreparedSqlObject sql,
            Map<String, Object> rule,
            ExtractionResult extraction,
            DatabaseResult business,
            DatabaseResult real,
            Map<String, Object> diagnosis,
            boolean explicitDiagnosis,
            boolean overviewMatched,
            ToolExecutionContext context) {
        String reportId = id("DDR_");
        long affected = longValue(diagnosis.get("affected_record_count")) == null
                ? estimatedAffectedCount(business, real)
                : longValue(diagnosis.get("affected_record_count"));
        Map<String, Object> safeReport = new LinkedHashMap<>();
        safeReport.put("report_id", reportId);
        safeReport.put("rule_id", sql.ruleId());
        safeReport.put("profile_id", rule.get("profile_id"));
        safeReport.put("stat_start", sql.statStart());
        safeReport.put("stat_end", sql.statEnd());
        safeReport.put("comparison_run_id", comparisonRunId);
        safeReport.put("extraction_id", extraction.extractionId());
        safeReport.put("baseline_run_id", real.runId());
        safeReport.put("business_run_id", business.runId());
        safeReport.put("real_run_id", real.runId());
        safeReport.put("business_result", business.safeMap());
        safeReport.put("real_result", real.safeMap());
        safeReport.put("conclusion_code", overviewMatched
                ? "EXPLICIT_DIAGNOSIS_COMPLETED"
                : "DUAL_DATABASE_RESULT_MISMATCH");
        safeReport.put("user_summary", overviewMatched
                ? "业务库与真实库概览一致，已按用户要求继续核对科室和患者明细。"
                : "业务库与真实库的指标结果不一致。");
        safeReport.put("trigger_type",
                explicitDiagnosis ? "explicit_user_diagnosis" : "automatic_mismatch");
        safeReport.put("extraction_status", extraction.status().name());
        safeReport.put("data_freshness",
                extraction.status() == ExtractionResult.Status.SKIPPED_DISABLED
                        ? "existing_snapshot_not_refreshed"
                        : "refreshed_by_current_run");
        safeReport.put("affected_record_count", affected);
        safeReport.put("evidence_limit",
                "逐条原因只以受保护明细快照为准；患者行不写入诊断报告或 Trace。");
        safeReport.put("dual_difference_diagnosis", diagnosis);
        safeReport.put("workflow_version", VERSION);
        diagnosisReports.saveDifference(
                reportId,
                context.agentContext().hospitalId(),
                sql.ruleId(),
                overviewMatched ? "用户主动要求排查指标。" : "双库概览结果不一致。",
                "请通过受权限保护的差异表核对仅业务库、仅真实库和字段判定不同记录。",
                safeReport,
                "completed".equals(diagnosis.get("status")) ? "completed" : "incomplete",
                sql.statStart() + " 至 " + sql.statEnd(),
                sql.sqlId());
        return reportId;
    }

    private SourceExtractionLease prepareExtraction(
            PreparedSqlObject sql,
            Map<String, Object> rule,
            String sourceSql,
            Map<String, Object> parameters,
            ToolExecutionContext context) {
        long started = System.nanoTime();
        String idempotencyKey = sha256(
                context.agentContext().traceId() + "|" + context.subtaskId()
                        + "|" + sql.ruleId() + "|" + sql.statStart() + "|" + sql.statEnd());
        Long hospitalSoid = extractionProperties.getHospitalSoid();
        @SuppressWarnings("unchecked")
        Map<String, Object> extractionContract = rule.get("extraction_contract") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        ExtractionRequest request = new ExtractionRequest(
                context.agentContext().traceId(),
                context.subtaskId(),
                context.agentContext().hospitalId(),
                context.agentContext().userId(),
                text(rule.get("knowledge_release_id")),
                sql.ruleId(),
                text(rule.get("profile_id")),
                parseTime(sql.statStart()),
                parseTime(sql.statEnd()),
                sourceSql,
                sha256(sourceSql),
                parameters,
                databaseQuery.sourceId(DatabaseRole.BUSINESS),
                databaseQuery.sourceId(DatabaseRole.REAL),
                idempotencyKey,
                hospitalSoid,
                extractionContract);
        SourceExtractionLease lease = extractionGateway.prepare(request);
        ExtractionResult result = lease.result();
        report(context, "source_data_extraction", "抽取数据到真实库",
                result.successful() ? "success" : "failed", elapsedMs(started),
                Map.of(
                        "extraction_id", blank(result.extractionId(), ""),
                        "status", result.status().name().toLowerCase(Locale.ROOT),
                        "extracted_rows", result.extractedRows(),
                        "inserted_rows", result.insertedRows(),
                        "updated_rows", result.updatedRows(),
                        "rejected_rows", result.rejectedRows()));
        return lease;
    }

    private DatabaseResult executeOverview(
            DatabaseRole role,
            PreparedSqlObject sql,
            String executableSql,
            Map<String, Object> contract,
            ToolExecutionContext context) {
        long started = System.nanoTime();
        List<Map<String, Object>> rows;
        try {
            rows = databaseQuery.execute(role, executableSql);
        } catch (DbHubMcpException exception) {
            report(context, "dual_overview_retry", "重试双库概览查询", "retrying",
                    elapsedMs(started), Map.of(
                            "source_role", role.value(),
                            "reason", "dbhub_mcp_failure"));
            rows = databaseQuery.execute(role, executableSql);
        }
        Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> resultMapping =
                objectMap(contract.get("overview_result_mapping"));
        String numeratorColumn = text(resultMapping.get("numerator_count"));
        String denominatorColumn = text(resultMapping.get("denominator_count"));
        String indexValueColumn = text(resultMapping.get("index_value"));
        String componentLeftColumn = text(resultMapping.get("component_left"));
        String componentRightColumn = text(resultMapping.get("component_right"));
        String sampleCountColumn = text(resultMapping.get("sample_count"));
        String targetValueColumn = text(resultMapping.get("target_value"));
        boolean ratioContract = !numeratorColumn.isBlank()
                && !denominatorColumn.isBlank();
        boolean scalarContract = !indexValueColumn.isBlank();
        Long numerator = null;
        Long denominator = null;
        Number resultValue;
        try {
            if (rows.isEmpty() && ratioContract) {
                numerator = 0L;
                denominator = 0L;
                resultValue = null;
            } else if (rows.isEmpty() && scalarContract) {
                resultValue = null;
            } else if (ratioContract) {
                if (!containsKey(first, numeratorColumn)
                        || !containsKey(first, denominatorColumn)) {
                    throw new OverviewResultContractException();
                }
                numerator = longValue(value(first, numeratorColumn));
                denominator = longValue(value(first, denominatorColumn));
                /*
                 * SUM 在空数据集上会返回 NULL，但结果列仍然存在。此时业务含义是统计
                 * 区间没有样本，应规范化为 0/0；只有结果列本身不存在时才属于契约错误。
                 */
                numerator = numerator == null ? 0L : numerator;
                denominator = denominator == null ? 0L : denominator;
                resultValue = rate(numerator, denominator);
            } else if (scalarContract) {
                if (!containsKey(first, indexValueColumn)) {
                    throw new OverviewResultContractException();
                }
                resultValue = numberValue(value(first, indexValueColumn));
            } else {
                throw new OverviewResultContractException();
            }
        } catch (NumberFormatException exception) {
            throw new OverviewResultContractException();
        }
        String componentLeft = rows.isEmpty()
                ? null : mappedText(first, componentLeftColumn);
        String componentRight = rows.isEmpty()
                ? null : mappedText(first, componentRightColumn);
        Long sampleCount = rows.isEmpty()
                ? null : mappedLong(first, sampleCountColumn);
        Number targetValue = rows.isEmpty()
                ? null : mappedNumber(first, targetValueColumn);
        String runId = id(role == DatabaseRole.BUSINESS ? "RUN_BUSINESS_" : "RUN_REAL_");
        long durationMs = elapsedMs(started);
        Map<String, Object> runContext = new LinkedHashMap<>(sql.contextSnapshot());
        Map<String, Object> executionContext =
                objectMap(runContext.get("execution_context"));
        executionContext.put("source_role", role.value());
        executionContext.put("source_id", databaseQuery.sourceId(role));
        executionContext.put("workflow_version", VERSION);
        runContext.put("execution_context", executionContext);
        // 明细预览根据该数据源重新查询，不能沿用准备 SQL 时的旧单库来源。
        runContext.put("db_source_id", databaseQuery.sourceId(role));
        objects.saveRun(
                runId, sql, "success", resultValue, numerator, denominator, "",
                durationMs, context.agentContext().userId(),
                runContext);
        Map<String, Object> traceOutput = new LinkedHashMap<>();
        traceOutput.put("source_id", databaseQuery.sourceId(role));
        traceOutput.put("run_id", runId);
        traceOutput.put("overview_sql_sha256", sha256(sql.sqlText()));
        traceOutput.put("result_contract", ratioContract ? "ratio" : "scalar");
        if (resultValue != null) {
            traceOutput.put("result_value", resultValue);
        }
        if (numerator != null) {
            traceOutput.put("numerator_count", numerator);
        }
        if (denominator != null) {
            traceOutput.put("denominator_count", denominator);
        }
        report(context,
                role == DatabaseRole.BUSINESS ? "business_overview" : "real_overview",
                role == DatabaseRole.BUSINESS ? "计算业务库概览" : "计算真实库概览",
                "success",
                durationMs,
                traceOutput);
        return new DatabaseResult(
                runId, role.value(), databaseQuery.sourceId(role),
                numerator, denominator, resultValue,
                componentLeft, componentRight, sampleCount, targetValue);
    }

    private Map<String, Object> diagnoseDetails(
            Map<String, Object> rule,
            Map<String, Object> parameters,
            Map<String, Object> contract,
            ToolExecutionContext context) {
        String departmentSql = text(rule.get("department_detail_sql"));
        String patientSql = text(rule.get("patient_detail_sql"));
        String departmentKey = text(contract.get("department_comparison_key"));
        String patientKey = text(contract.get("patient_comparison_key"));
        if (departmentSql.isBlank() || patientSql.isBlank()
                || departmentKey.isBlank() || patientKey.isBlank()
                || !validator.validateReadOnly(departmentSql).ok()
                || !validator.validateReadOnly(patientSql).ok()) {
            return Map.of(
                    "status", "incomplete",
                    "code", "DETAIL_COMPARISON_CONTRACT_MISSING",
                    "message", "双库概览不一致，但明细 SQL 或比较键尚未通过验证。");
        }
        try {
            String boundDepartment = binder.bind(departmentSql, parameters);
            String boundPatient = binder.bind(patientSql, parameters);
            long departmentStarted = System.nanoTime();
            PairComparison department = compare(
                    databaseQuery.execute(DatabaseRole.BUSINESS, boundDepartment),
                    databaseQuery.execute(DatabaseRole.REAL, boundDepartment),
                    departmentKey,
                    strings(contract.get("department_compare_fields")));
            report(context, "dual_department_detail", "核对科室差异", "success",
                    elapsedMs(departmentStarted),
                    withHash(department.safeMap(), "department_sql_sha256",
                            sha256(departmentSql)));
            long patientStarted = System.nanoTime();
            PairComparison patient = compare(
                    databaseQuery.execute(DatabaseRole.BUSINESS, boundPatient),
                    databaseQuery.execute(DatabaseRole.REAL, boundPatient),
                    patientKey,
                    strings(contract.get("patient_compare_fields")));
            report(context, "dual_patient_detail", "核对患者明细", "success",
                    elapsedMs(patientStarted),
                    withHash(patient.safeMap(), "patient_sql_sha256",
                            sha256(patientSql)));
            return Map.of(
                    "status", "completed",
                    "department_comparison", department.safeMap(),
                    "patient_comparison", patient.safeMap(),
                    "affected_record_count",
                    department.businessOnly() + department.realOnly()
                            + department.different()
                            + patient.businessOnly() + patient.realOnly() + patient.different());
        } catch (RuntimeException exception) {
            return Map.of(
                    "status", "incomplete",
                    "code", "DUAL_DETAIL_QUERY_FAILED",
                    "message", "双库概览不一致，但科室或患者明细查询未全部完成。");
        }
    }

    private static PairComparison compare(
            List<Map<String, Object>> businessRows,
            List<Map<String, Object>> realRows,
            String key,
            List<String> compareFields) {
        IndexedRows businessIndex = index(businessRows, key);
        IndexedRows realIndex = index(realRows, key);
        Map<String, Map<String, Object>> business = businessIndex.rows();
        Map<String, Map<String, Object>> real = realIndex.rows();
        Set<String> all = new LinkedHashSet<>(business.keySet());
        all.addAll(real.keySet());
        long both = 0;
        long businessOnly = 0;
        long realOnly = 0;
        long different = 0;
        for (String id : all) {
            Map<String, Object> left = business.get(id);
            Map<String, Object> right = real.get(id);
            if (left == null) {
                realOnly++;
            } else if (right == null) {
                businessOnly++;
            } else {
                both++;
                if (fieldsDifferent(left, right, compareFields, key)) {
                    different++;
                }
            }
        }
        return new PairComparison(
                both, businessOnly, realOnly, different,
                businessRows.size(), realRows.size(),
                businessIndex.duplicateCount(), realIndex.duplicateCount());
    }

    private static IndexedRows index(
            List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        long duplicates = 0;
        for (Map<String, Object> row : rows) {
            Object value = value(row, key);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("明细结果缺少比较键 " + key);
            }
            if (result.putIfAbsent(String.valueOf(value), row) != null) {
                duplicates++;
            }
        }
        return new IndexedRows(result, duplicates);
    }

    private static boolean fieldsDifferent(
            Map<String, Object> left,
            Map<String, Object> right,
            List<String> compareFields,
            String key) {
        List<String> fields = compareFields.isEmpty()
                ? left.keySet().stream()
                        .filter(field -> !field.equalsIgnoreCase(key))
                        .toList()
                : compareFields;
        for (String field : fields) {
            if (!String.valueOf(value(left, field))
                    .equals(String.valueOf(value(right, field)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean schemaCompatible(Map<String, Object> contract) {
        if (!Boolean.TRUE.equals(contract.get("schema_compatible"))) {
            return false;
        }
        Set<String> sources = new LinkedHashSet<>(strings(contract.get("verified_source_roles")));
        Map<String, Object> verification = objectMap(contract.get("source_verification"));
        Map<String, Object> business = objectMap(verification.get("business"));
        Map<String, Object> real = objectMap(verification.get("real"));
        Map<String, Object> resultMapping =
                objectMap(contract.get("overview_result_mapping"));
        Set<String> allowedFields =
                new LinkedHashSet<>(strings(contract.get("allowed_compare_fields")));
        List<String> requestedFields = new java.util.ArrayList<>();
        requestedFields.addAll(strings(contract.get("department_compare_fields")));
        requestedFields.addAll(strings(contract.get("patient_compare_fields")));
        requestedFields.add(text(contract.get("numerator_classification_field")));
        return sources.contains("business")
                && sources.contains("real")
                && sourceVerified(business)
                && sourceVerified(real)
                && !text(resultMapping.get("numerator_count")).isBlank()
                && !text(resultMapping.get("denominator_count")).isBlank()
                && !text(contract.get("numerator_classification_field")).isBlank()
                && !allowedFields.isEmpty()
                && allowedFields.containsAll(requestedFields);
    }

    private static boolean sourceVerified(Map<String, Object> source) {
        return "validated".equals(text(source.get("metadata_status")))
                && "validated".equals(text(source.get("compile_status")));
    }

    private static Map<String, Object> mismatchSummary(
            DatabaseResult business,
            DatabaseResult real,
            Map<String, Object> diagnosis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result_contract",
                business.ratioContract() && real.ratioContract() ? "ratio" : "scalar");
        result.put("business_result_value", business.resultValue());
        result.put("real_result_value", real.resultValue());
        putIfPresent(result, "business_target_value", business.targetValue());
        putIfPresent(result, "real_target_value", real.targetValue());
        result.put("target_conflict", targetConflict(business, real));
        if (business.ratioContract() && real.ratioContract()) {
            result.put("numerator_delta", business.numerator() - real.numerator());
            result.put("denominator_delta", business.denominator() - real.denominator());
        }
        result.put("diagnosis_status",
                diagnosis.getOrDefault("status", "incomplete"));
        return result;
    }

    private static long estimatedAffectedCount(
            DatabaseResult business,
            DatabaseResult real) {
        if (business.ratioContract() && real.ratioContract()) {
            return Math.abs(business.numerator() - real.numerator())
                    + Math.abs(business.denominator() - real.denominator());
        }
        return business.matches(real) ? 0 : 1;
    }

    private static Number rate(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static Number numberValue(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal decimal(Number value) {
        return new BigDecimal(value.toString()).stripTrailingZeros();
    }

    private static boolean targetConflict(DatabaseResult business, DatabaseResult real) {
        return business.targetValue() != null
                && real.targetValue() != null
                && decimal(business.targetValue())
                        .compareTo(decimal(real.targetValue())) != 0;
    }

    private static Number resolvedTarget(DatabaseResult business, DatabaseResult real) {
        if (targetConflict(business, real)) {
            return null;
        }
        return real.targetValue() != null ? real.targetValue() : business.targetValue();
    }

    private static String targetSource(DatabaseResult business, DatabaseResult real) {
        if (business.targetValue() != null && real.targetValue() != null) {
            return "business_and_real";
        }
        if (real.targetValue() != null) {
            return "real";
        }
        return business.targetValue() != null ? "business" : "";
    }

    private static Number profileTarget(Map<String, Object> contract) {
        try {
            return numberValue(
                    objectMap(contract.get("result_contract")).get("target_value"));
        } catch (NumberFormatException exception) {
            throw new OverviewResultContractException();
        }
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean containsKey(Map<String, Object> row, String key) {
        return row.keySet().stream().anyMatch(column -> column.equalsIgnoreCase(key));
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    private static String mappedText(Map<String, Object> row, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        if (!containsKey(row, column)) {
            throw new OverviewResultContractException();
        }
        Object value = value(row, column);
        return value == null || String.valueOf(value).isBlank()
                ? null : String.valueOf(value).strip();
    }

    private static Long mappedLong(Map<String, Object> row, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        if (!containsKey(row, column)) {
            throw new OverviewResultContractException();
        }
        try {
            return longValue(value(row, column));
        } catch (NumberFormatException exception) {
            throw new OverviewResultContractException();
        }
    }

    private static Number mappedNumber(Map<String, Object> row, String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        if (!containsKey(row, column)) {
            throw new OverviewResultContractException();
        }
        try {
            return numberValue(value(row, column));
        } catch (NumberFormatException exception) {
            throw new OverviewResultContractException();
        }
    }

    private static void putIfPresent(
            Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> withHash(
            Map<String, Object> values,
            String key,
            String hash) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put(key, hash);
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static LocalDateTime parseTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException ignored) {
            return LocalDateTime.parse(value, SQL_TIME);
        }
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成执行对象哈希", exception);
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static void report(
            ToolExecutionContext context,
            String nodeName,
            String label,
            String status,
            long durationMs,
            Map<String, Object> output) {
        context.runState().reportProgress(new WorkflowProgress(
                nodeName, label, status, durationMs, output));
    }

    private record DatabaseResult(
            String runId,
            String sourceRole,
            String sourceId,
            Long numerator,
            Long denominator,
            Number resultValue,
            String componentLeft,
            String componentRight,
            Long sampleCount,
            Number targetValue) {
        boolean ratioContract() {
            return numerator != null && denominator != null;
        }

        boolean matches(DatabaseResult other) {
            if (ratioContract() && other.ratioContract()) {
                return numerator.equals(other.numerator)
                        && denominator.equals(other.denominator);
            }
            if (ratioContract() != other.ratioContract()) {
                return false;
            }
            if (resultValue == null || other.resultValue == null) {
                return resultValue == null && other.resultValue == null;
            }
            return decimal(resultValue).compareTo(decimal(other.resultValue)) == 0;
        }

        Map<String, Object> safeMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("run_id", runId);
            result.put("source_role", sourceRole);
            result.put("source_id", sourceId);
            result.put("result_contract", ratioContract() ? "ratio" : "scalar");
            result.put("result_value", resultValue);
            if (numerator != null) {
                result.put("numerator_count", numerator);
            }
            if (denominator != null) {
                result.put("denominator_count", denominator);
            }
            putIfPresent(result, "component_left", componentLeft);
            putIfPresent(result, "component_right", componentRight);
            putIfPresent(result, "sample_count", sampleCount);
            putIfPresent(result, "target_value", targetValue);
            return result;
        }
    }

    private static final class OverviewResultContractException extends RuntimeException {
    }

    private record PairComparison(
            long both,
            long businessOnly,
            long realOnly,
            long different,
            long businessCount,
            long realCount,
            long businessDuplicateCount,
            long realDuplicateCount) {
        Map<String, Object> safeMap() {
            return Map.of(
                    "both_count", both,
                    "business_only_count", businessOnly,
                    "real_only_count", realOnly,
                    "different_count", different,
                    "business_count", businessCount,
                    "real_count", realCount,
                    "business_duplicate_count", businessDuplicateCount,
                    "real_duplicate_count", realDuplicateCount);
        }
    }

    private record IndexedRows(
            Map<String, Map<String, Object>> rows,
            long duplicateCount) {}
}

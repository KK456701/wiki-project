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
import com.hospital.wikiagent.agent.planning.StatPeriodPolicy;
import com.hospital.wikiagent.agent.runtime.AgentRunState.WorkflowProgress;
import com.hospital.wikiagent.agent.runtime.AgentRunState.ExtractionReceipt;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;

/**
 * 在源数据抽取完成后，按固定顺序执行业务库与真实库指标计算。
 *
 * <p>该 Workflow 不由模型选择数据库或 SQL。所有 SQL 均来自同一个已发布 Profile，
 * 两个数据库使用完全相同的 SQL 和参数。概览分子、分母一致时立即结束；不一致时
 * 才执行科室和患者明细，并且只返回安全的集合统计，不把患者行写入 Trace。</p>
 */
@Component
public class DualDatabaseIndicatorExecutionWorkflow {
    public static final String VERSION = "dual-database-indicator-workflow-v1";
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

    public boolean required() {
        return extractionProperties.required();
    }

    public ToolResult execute(
            PreparedSqlObject sql,
            Map<String, Object> rule,
            String executableOverview,
            Map<String, Object> boundParameters,
            ToolExecutionContext context) {
        if (!required()) {
            return ToolResult.failure(
                    "unavailable", "DUAL_DATABASE_WORKFLOW_DISABLED",
                    "双库计算尚未启用。", false);
        }
        if (!extractionGateway.available()) {
            return ToolResult.failure(
                    "unavailable", "EXTRACTION_GATEWAY_UNAVAILABLE",
                    "源数据抽取接口尚未接入，不能执行双库计算。", false);
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
        StatPeriodPolicy.Validation period = StatPeriodPolicy.validate(statStart, statEnd);
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
        if (!schemaCompatible(contract)) {
            return ToolResult.failure(
                    "validation_failed", "DUAL_DATABASE_SCHEMA_INCOMPATIBLE",
                    "当前 Profile 尚未确认业务库与真实库具备同构查询对象，未执行抽取或数据库查询。",
                    false);
        }

        String sourceSql = text(rule.get("source_extract_sql"));
        if (sourceSql.isBlank() || !validator.validateReadOnly(sourceSql).ok()) {
            return ToolResult.failure(
                    "validation_failed", "SOURCE_EXTRACT_SQL_UNAVAILABLE",
                    "当前 Profile 的源数据 SQL 尚未通过可执行校验。", false);
        }
        if (!validator.validateReadOnly(sql.sqlText()).ok()) {
            return ToolResult.failure(
                    "validation_failed", "SQL_REVALIDATION_FAILED",
                    "概览 SQL 在双库执行前未通过只读安全校验。", false);
        }
        report(context, "source_extraction_prepare", "准备源数据抽取", "success", 0,
                Map.of(
                        "source_sql_sha256", sha256(sourceSql),
                        "release_id", text(rule.get("knowledge_release_id")),
                        "rule_id", sql.ruleId(),
                        "profile_id", text(rule.get("profile_id"))));

        try (HospitalExecutionLock.Lease ignored =
                     executionLock.acquire(context.agentContext().hospitalId())) {
            ExtractionResult extraction = extract(
                    sql, rule, sourceSql, boundParameters, context);
            if (!extraction.successful()) {
                return ToolResult.failure(
                        "error",
                        blank(extraction.errorCode(), "SOURCE_EXTRACTION_FAILED"),
                        blank(extraction.message(), "源数据抽取失败，未执行双库计算。"),
                        false);
            }

            DatabaseResult business;
            try {
                business = executeOverview(
                        DatabaseRole.BUSINESS, sql, executableOverview, contract, context);
            } catch (RuntimeException exception) {
                return ToolResult.failure(
                        "error", "BUSINESS_DATABASE_OVERVIEW_FAILED",
                        "业务库概览计算失败，未形成双库比较结论。", false);
            }

            DatabaseResult real;
            try {
                real = executeOverview(
                        DatabaseRole.REAL, sql, executableOverview, contract, context);
            } catch (RuntimeException exception) {
                return ToolResult.failure(
                        "error", "REAL_DATABASE_OVERVIEW_FAILED",
                        "真实库概览计算失败，未形成双库比较结论。", false);
            }

            boolean matched = business.numerator() == real.numerator()
                    && business.denominator() == real.denominator();
            String comparisonRunId = id("RUN_COMPOSITE_");
            Map<String, Object> diagnosis = matched
                    ? Map.of("status", "skipped", "reason", "overview_matched")
                    : new LinkedHashMap<>(
                            diagnoseDetails(rule, boundParameters, contract, context));
            String comparisonStatus = matched ? "matched" : "mismatched";
            String diagnosisReportId = "";
            Map<String, Object> mismatch = matched
                    ? Map.of()
                    : Map.of(
                            "numerator_delta", business.numerator() - real.numerator(),
                            "denominator_delta", business.denominator() - real.denominator(),
                            "diagnosis_status", diagnosis.getOrDefault("status", "incomplete"));
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
            if (!matched) {
                try {
                    diagnosisReportId = saveDiagnosisReport(
                            comparisonRunId, sql, rule, extraction, business, real,
                            diagnosis, context);
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
            data.put("comparison_status", comparisonStatus);
            data.put("business_result", business.safeMap());
            data.put("real_result", real.safeMap());
            data.put("numerator_count", real.numerator());
            data.put("denominator_count", real.denominator());
            data.put("result_value", real.rate());
            data.put("no_sample", real.denominator() == 0);
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
                            ? "业务库与真实库的分子、分母一致。"
                            : "业务库与真实库结果不一致，已执行受控明细诊断。",
                    data);
        }
    }

    private String saveDiagnosisReport(
            String comparisonRunId,
            PreparedSqlObject sql,
            Map<String, Object> rule,
            ExtractionResult extraction,
            DatabaseResult business,
            DatabaseResult real,
            Map<String, Object> diagnosis,
            ToolExecutionContext context) {
        String reportId = id("DDR_");
        long affected = longValue(diagnosis.get("affected_record_count")) == null
                ? Math.abs(business.numerator() - real.numerator())
                        + Math.abs(business.denominator() - real.denominator())
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
        safeReport.put("conclusion_code", "DUAL_DATABASE_RESULT_MISMATCH");
        safeReport.put("user_summary", "业务库与真实库的分子或分母不一致。");
        safeReport.put("affected_record_count", affected);
        safeReport.put("evidence_limit",
                "逐条原因只以受保护明细快照为准；患者行不写入诊断报告或 Trace。");
        safeReport.put("dual_difference_diagnosis", diagnosis);
        safeReport.put("workflow_version", VERSION);
        diagnosisReports.saveDifference(
                reportId,
                context.agentContext().hospitalId(),
                sql.ruleId(),
                "双库概览结果不一致。",
                "请通过受权限保护的差异表核对仅业务库、仅真实库和字段判定不同记录。",
                safeReport,
                "completed".equals(diagnosis.get("status")) ? "completed" : "incomplete",
                sql.statStart() + " 至 " + sql.statEnd(),
                sql.sqlId());
        return reportId;
    }

    private ExtractionResult extract(
            PreparedSqlObject sql,
            Map<String, Object> rule,
            String sourceSql,
            Map<String, Object> parameters,
            ToolExecutionContext context) {
        long started = System.nanoTime();
        String idempotencyKey = sha256(
                context.agentContext().traceId() + "|" + context.subtaskId()
                        + "|" + sql.ruleId() + "|" + sql.statStart() + "|" + sql.statEnd());
        ExtractionReceipt cached = context.runState().extractionReceipt(idempotencyKey);
        if (cached != null) {
            report(context, "source_data_extraction", "抽取数据到真实库",
                    "success", elapsedMs(started),
                    Map.of(
                            "extraction_id", blank(cached.extractionId(), ""),
                            "status", "reused",
                            "cache_reused", true));
            return new ExtractionResult(
                    cached.extractionId(),
                    ExtractionResult.Status.SUCCESS,
                    cached.extractedRows(),
                    cached.insertedRows(),
                    cached.updatedRows(),
                    cached.rejectedRows(),
                    java.time.Instant.now(),
                    cached.sourceSnapshotId(),
                    cached.targetSnapshotId(),
                    "",
                    "复用本轮已完成的源数据抽取。");
        }
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
                idempotencyKey);
        ExtractionResult result = extractionGateway.extract(request);
        if (result.successful()) {
            context.runState().extractionReceipt(idempotencyKey, new ExtractionReceipt(
                    result.extractionId(),
                    result.extractedRows(),
                    result.insertedRows(),
                    result.updatedRows(),
                    result.rejectedRows(),
                    result.sourceSnapshotId(),
                    result.targetSnapshotId()));
        }
        report(context, "source_data_extraction", "抽取数据到真实库",
                result.successful() ? "success" : "failed", elapsedMs(started),
                Map.of(
                        "extraction_id", blank(result.extractionId(), ""),
                        "status", result.status().name().toLowerCase(Locale.ROOT),
                        "extracted_rows", result.extractedRows(),
                        "inserted_rows", result.insertedRows(),
                        "updated_rows", result.updatedRows(),
                        "rejected_rows", result.rejectedRows()));
        return result;
    }

    private DatabaseResult executeOverview(
            DatabaseRole role,
            PreparedSqlObject sql,
            String executableSql,
            Map<String, Object> contract,
            ToolExecutionContext context) {
        long started = System.nanoTime();
        List<Map<String, Object>> rows = databaseQuery.execute(role, executableSql);
        Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
        Map<String, Object> resultMapping =
                objectMap(contract.get("overview_result_mapping"));
        String numeratorColumn = text(resultMapping.get("numerator_count"));
        String denominatorColumn = text(resultMapping.get("denominator_count"));
        Long numerator = longValue(value(first, numeratorColumn));
        Long denominator = longValue(value(first, denominatorColumn));
        if (numerator == null || denominator == null) {
            throw new IllegalStateException("概览结果缺少分子或分母");
        }
        String runId = id(role == DatabaseRole.BUSINESS ? "RUN_BUSINESS_" : "RUN_REAL_");
        Number rate = rate(numerator, denominator);
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
                runId, sql, "success", rate, numerator, denominator, "",
                durationMs, context.agentContext().userId(),
                runContext);
        report(context,
                role == DatabaseRole.BUSINESS ? "business_overview" : "real_overview",
                role == DatabaseRole.BUSINESS ? "计算业务库概览" : "计算真实库概览",
                "success",
                durationMs,
                Map.of(
                        "source_id", databaseQuery.sourceId(role),
                        "run_id", runId,
                        "overview_sql_sha256", sha256(sql.sqlText()),
                        "numerator_count", numerator,
                        "denominator_count", denominator));
        return new DatabaseResult(
                runId, databaseQuery.sourceId(role), numerator, denominator, rate);
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

    private static Number rate(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
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
            String sourceId,
            long numerator,
            long denominator,
            Number rate) {
        Map<String, Object> safeMap() {
            return Map.of(
                    "run_id", runId,
                    "source_id", sourceId,
                    "numerator_count", numerator,
                    "denominator_count", denominator,
                    "result_value", rate);
        }
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

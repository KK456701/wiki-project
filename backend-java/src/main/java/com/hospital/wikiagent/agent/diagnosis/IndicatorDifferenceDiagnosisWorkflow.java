package com.hospital.wikiagent.agent.diagnosis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.IndicatorSqlTools;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.agent.upload.UploadedIndicatorTools;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.metadata.MetadataCatalogClient;
import com.hospital.wikiagent.rules.RuleReadRepository;

/**
 * 对“用户结果与系统结果不一致”执行固定、可审计的分层诊断。
 *
 * <p>本类不是第二套 Agent。它是 Compiled Plan 中一个确定性业务能力：模型只负责识别
 * 差异诊断目标，服务端固定执行范围预检、实时结构核验、口径反事实、记录集合和数据质量
 * 检查。所有 SQL 都来自 Wiki 模板或本类允许列表检查器，不能接受模型和用户提交的 SQL。</p>
 *
 * <p>候选口径采用分级归因：分子、分母和指标率完整一致时确认口径原因；部分聚合值一致且
 * 口径描述或文件字段支持时标记为高度相关，并继续核对剩余记录；只有一个未标注数值一致时
 * 仅标记为可能相关。患者级行只留在现有短期明细对象中，本工具返回并保存的报告只包含计数、
 * 对象编号和结论。</p>
 */
@Component
public class IndicatorDifferenceDiagnosisWorkflow {
    private static final int MAX_CALIBER_CANDIDATES = 5;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern USER_VALUE = Pattern.compile(
            "(?:我们|我方|用户|文件|表格)[^\\d]{0,12}(\\d+(?:\\.\\d+)?)");
    private static final Pattern SYSTEM_VALUE = Pattern.compile(
            "(?:你们|系统|本院|平台)[^\\d]{0,12}(\\d+(?:\\.\\d+)?)");

    private final RuleReadRepository rules;
    private final IndicatorSqlTools sqlTools;
    private final UploadedIndicatorTools uploadTools;
    private final MetadataCatalogClient metadataCatalog;
    private final DbHubProperties dbHub;
    private final IndicatorBusinessQueryClient businessQuery;
    private final DiagnosisReportRepository reports;

    public IndicatorDifferenceDiagnosisWorkflow(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools,
            UploadedIndicatorTools uploadTools,
            MetadataCatalogClient metadataCatalog,
            DbHubProperties dbHub,
            IndicatorBusinessQueryClient businessQuery,
            DiagnosisReportRepository reports) {
        this.rules = rules;
        this.sqlTools = sqlTools;
        this.uploadTools = uploadTools;
        this.metadataCatalog = metadataCatalog;
        this.dbHub = dbHub;
        this.businessQuery = businessQuery;
        this.reports = reports;
    }

    /**
     * 执行完整差异诊断。Workflow 内的业务失败会形成可回答的诊断报告，而不是触发
     * Replanner；只有参数本身非法时才返回普通工具失败。
     */
    public ToolResult diagnose(Input input, ToolExecutionContext context) {
        AgentRunState state = context.runState();
        if (state.currentRuleId() == null || !state.currentRuleId().equals(input.ruleId())) {
            return ToolResult.failure(
                    "validation_failed", "RULE_NOT_VERIFIED",
                    "该指标尚未经过规则搜索或读取，不能启动差异诊断。", false);
        }

        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(input.statStartTime());
            end = LocalDateTime.parse(input.statEndTime());
        } catch (DateTimeParseException exception) {
            return ToolResult.failure(
                    "validation_failed", "STAT_PERIOD_INVALID",
                    "差异诊断需要明确且格式正确的统计开始时间和结束时间。", false);
        }
        if (!start.isBefore(end)) {
            return ToolResult.failure(
                    "validation_failed", "STAT_PERIOD_INVALID",
                    "统计开始时间必须早于结束时间。", false);
        }

        Map<String, Object> rule = rules.effectiveRule(input.ruleId(), context.agentContext().hospitalId());
        Map<String, Object> mapping = rules.fieldMapping(input.ruleId(), context.agentContext().hospitalId());
        DiagnosticExecution execution = new DiagnosticExecution(input, context, rule, mapping, start, end);

        Map<String, Object> preflight = preflight(execution);
        addLayer(execution, preflight);
        if (Boolean.TRUE.equals(preflight.get("blocking"))) {
            return finish(execution, "INSUFFICIENT_EXTERNAL_EVIDENCE", 1,
                    "诊断范围或外部证据不完整，当前不能进行同口径比较。");
        }
        Map<String, Object> initialPeriodConflict =
                periodConflict(execution, execution.initialUploadInspection);
        if (!initialPeriodConflict.isEmpty()) {
            addLayer(execution, initialPeriodConflict);
            return finish(execution, "INSUFFICIENT_EXTERNAL_EVIDENCE", 1,
                    "上传文件统计区间与本轮统计区间冲突，需要先确认比较范围。");
        }

        Map<String, Object> structure = structureLayer(execution);
        addLayer(execution, structure);
        if (Boolean.TRUE.equals(structure.get("blocking"))) {
            return finish(execution, "STRUCTURE_BLOCKING", 2,
                    "实时数据库结构或医院映射存在阻断项，当前结果不能继续核验。");
        }

        ToolResult baseline = runBaseline(execution);
        addLayer(execution, execution.baselineLayer);
        if (!baseline.ok()) {
            return finish(execution, "STRUCTURE_BLOCKING", 3,
                    "当前生效口径无法完成受控 SQL 试运行，尚不能比较结果。");
        }

        // 有文件时，必须在当前基准试运行证据之后重新分析，避免误用历史会话中的旧结果。
        if (input.fileKey() != null) {
            execution.uploadComparison = analyzeUploadAgainst(baseline, execution);
            Map<String, Object> conflict = periodConflict(execution, execution.uploadComparison);
            if (!conflict.isEmpty()) {
                addLayer(execution, conflict);
                return finish(execution, "INSUFFICIENT_EXTERNAL_EVIDENCE", 1,
                        "上传文件统计区间与本轮统计区间冲突，需要先确认比较范围。");
            }
        }

        Map<String, Object> caliber = caliberLayer(execution);
        addLayer(execution, caliber);
        if (Boolean.TRUE.equals(caliber.get("cause_confirmed"))) {
            return finish(execution, "CALIBER_CAUSE_CONFIRMED", 4,
                    text(caliber.get("conclusion")));
        }

        Map<String, Object> recordSet = recordSetLayer(execution);
        addLayer(execution, recordSet);
        if (Boolean.TRUE.equals(recordSet.get("cause_confirmed"))
                && Boolean.TRUE.equals(recordSet.get("difference_fully_explained"))) {
            return finish(execution, "RECORD_SET_DIFF_CONFIRMED", 5,
                    text(recordSet.get("conclusion")));
        }

        Map<String, Object> quality = qualityLayer(execution);
        addLayer(execution, quality);
        if (Boolean.TRUE.equals(quality.get("cause_confirmed"))) {
            return finish(execution, "DATA_QUALITY_CAUSE_CONFIRMED", 6,
                    text(quality.get("conclusion")));
        }
        if (Boolean.TRUE.equals(recordSet.get("cause_confirmed"))) {
            return finish(execution, "RECORD_SET_DIFF_CONFIRMED", 6,
                    text(recordSet.get("conclusion")));
        }
        if (Boolean.TRUE.equals(caliber.get("cause_likely"))) {
            return finish(execution, "CALIBER_CAUSE_LIKELY", 6,
                    text(caliber.get("likely_conclusion")));
        }

        boolean externalEvidence = hasExternalValues(execution) || rowLevelAvailable(execution.uploadComparison);
        boolean unresolvedQualityAnomaly = longValue(quality.get("affected_record_count")) > 0;
        String conclusionCode = externalEvidence && !unresolvedQualityAnomaly
                ? "SYSTEM_RESULT_VERIFIED"
                : "INSUFFICIENT_EXTERNAL_EVIDENCE";
        String conclusion = externalEvidence && !unresolvedQualityAnomaly
                ? "当前证据下，系统生效口径、实时结构和聚合结果内部一致；尚未获得足以确认外部差值来源的逐条证据。"
                : unresolvedQualityAnomaly
                        ? "发现系统数据质量异常，但当前证据不能证明这些异常就是双方差值的原因。"
                        : "当前缺少可与系统逐条核对的外部证据，不能推断用户侧具体记录为何不同。";
        return finish(execution, conclusionCode, 7, conclusion);
    }

    private Map<String, Object> preflight(DiagnosticExecution execution) {
        long started = System.nanoTime();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layer", 1);
        data.put("node_name", "诊断范围预检");
        data.put("status", "passed");
        data.put("rule_id", execution.input.ruleId());
        data.put("hospital_id", execution.context.agentContext().hospitalId());
        data.put("stat_start", execution.input.statStartTime());
        data.put("stat_end", execution.input.statEndTime());
        data.put("period_field", periodBusinessField(execution.rule));
        data.put("deduplication_key", deduplicationField(execution.rule));
        data.put("claimed_values", claimedValues(execution.input.issueDescription()));

        if (execution.input.fileKey() == null) {
            data.put("file_evidence", "not_provided");
            if (claimedValues(execution.input.issueDescription()).isEmpty()) {
                data.put("status", "blocked");
                data.put("blocking", true);
                data.put("reason_code", "EXTERNAL_RESULT_MISSING");
                data.put("message", "请提供用户侧分子、分母、指标率或可核对的 Excel 文件。");
            }
        } else {
            ToolResult inspected = uploadTools.analyze(
                    new UploadedIndicatorTools.Input(execution.input.fileKey()), execution.context);
            if (!inspected.ok()) {
                data.put("status", "blocked");
                data.put("blocking", true);
                data.put("reason_code", inspected.code());
                data.put("message", inspected.summary());
            } else {
                execution.initialUploadInspection = inspected;
                data.put("file_evidence", Map.of(
                        "file_key", execution.input.fileKey(),
                        "file_name", inspected.data().getOrDefault("file_name", ""),
                        "row_count", inspected.data().getOrDefault("row_count", 0),
                        "columns", inspected.data().getOrDefault("columns", List.of()),
                        "file_evidence_type", inspected.data().getOrDefault("file_evidence_type", "unknown"),
                        "uploaded_rule_id", inspected.data().getOrDefault("uploaded_rule_id", ""),
                        "uploaded_stat_period", inspected.data().getOrDefault("uploaded_stat_period", ""),
                        "comparison_level", inspected.data().getOrDefault("comparison_level", "none")));
                String fileRuleId = text(inspected.data().get("uploaded_rule_id"));
                if (!fileRuleId.isBlank() && !execution.input.ruleId().equalsIgnoreCase(fileRuleId)) {
                    data.put("status", "blocked");
                    data.put("blocking", true);
                    data.put("reason_code", "FILE_INDICATOR_MISMATCH");
                    data.put("message", "上传文件指标编号与当前指标不一致。");
                }
            }
        }
        data.put("duration_ms", elapsedMs(started));
        return Map.copyOf(data);
    }

    /**
     * 结构层始终读取 DBHub 的实时 INFORMATION_SCHEMA，而不是仅相信 Wiki 中上次同步的
     * metadata_items。这样数据库字段被删除或改型时能够在 SQL 执行前被阻断。
     */
    private Map<String, Object> structureLayer(DiagnosticExecution execution) {
        long started = System.nanoTime();
        List<Map<String, Object>> checks = new ArrayList<>();
        Map<String, Object> fields = objectMap(execution.mapping.get("fields"));
        Map<String, Object> contracts = objectMap(
                objectMap(execution.rule.get("field_contract")).get("business_fields"));
        String database = firstNonBlank(text(execution.mapping.get("db_name")), dbHub.getDatabaseName());
        String schema = firstNonBlank(text(execution.mapping.get("schema")), dbHub.getSchemaName());
        Set<String> requiredTables = new LinkedHashSet<>();
        for (Object value : fields.values()) {
            PhysicalField ref = physicalField(value);
            if (ref != null) requiredTables.add(ref.table());
        }

        boolean blocking = false;
        try {
            Set<String> liveTables = new LinkedHashSet<>();
            for (Map<String, Object> row : metadataCatalog.listTables(database, schema)) {
                String table = valueIgnoreCase(row, "TABLE_NAME");
                if (!table.isBlank()) liveTables.add(table.toUpperCase(Locale.ROOT));
            }
            for (String table : requiredTables) {
                if (!liveTables.contains(table.toUpperCase(Locale.ROOT))) {
                    blocking = true;
                    checks.add(check("missing_table", "fail",
                            "实时数据库缺少依赖表 " + table + "。"));
                    continue;
                }
                Map<String, String> liveColumns = new LinkedHashMap<>();
                for (Map<String, Object> row : metadataCatalog.listColumns(database, schema, table)) {
                    liveColumns.put(
                            valueIgnoreCase(row, "COLUMN_NAME").toUpperCase(Locale.ROOT),
                            valueIgnoreCase(row, "DATA_TYPE").toLowerCase(Locale.ROOT));
                }
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    PhysicalField ref = physicalField(entry.getValue());
                    if (ref == null || !table.equalsIgnoreCase(ref.table())) continue;
                    String actual = liveColumns.get(ref.column().toUpperCase(Locale.ROOT));
                    if (actual == null) {
                        blocking = true;
                        checks.add(check("missing_column", "fail",
                                "实时数据库缺少字段 " + table + "." + ref.column() + "。"));
                        continue;
                    }
                    String expected = text(objectMap(contracts.get(entry.getKey())).get("type"))
                            .toLowerCase(Locale.ROOT);
                    if (!typesCompatible(expected, actual)) {
                        blocking = true;
                        checks.add(check("type_mismatch", "fail",
                                entry.getKey() + " 类型不兼容：期望 " + expected + "，实际 " + actual + "。"));
                    }
                }
            }
        } catch (RuntimeException exception) {
            blocking = true;
            checks.add(check("metadata_unavailable", "fail",
                    "无法通过 DBHub 读取实时数据库元数据。"));
        }

        if (!"confirmed".equalsIgnoreCase(text(execution.mapping.get("status")))) {
            blocking = true;
            checks.add(check("mapping_unconfirmed", "fail", "医院字段映射尚未确认。"));
        }
        if (requiredTables.size() > 1 && listOfMaps(execution.mapping.get("relations")).isEmpty()) {
            blocking = true;
            checks.add(check("relation_missing", "fail", "跨表指标缺少已确认关联关系。"));
        }
        if (checks.isEmpty()) {
            checks.add(check("realtime_structure", "pass",
                    "Wiki 字段契约、医院映射和实时数据库元数据一致。"));
        }
        return layer(2, "实时结构核验", blocking ? "blocked" : "passed",
                blocking, false, checks, elapsedMs(started));
    }

    private ToolResult runBaseline(DiagnosticExecution execution) {
        long started = System.nanoTime();
        ToolResult prepared = sqlTools.prepare(
                new IndicatorSqlTools.PrepareInput(
                        execution.input.ruleId(),
                        execution.input.statStartTime(),
                        execution.input.statEndTime()),
                execution.context);
        if (!prepared.ok()) {
            execution.baselineLayer = layer(3, "执行当前口径", "failed", true, false,
                    List.of(check(prepared.code(), "fail", prepared.summary())), elapsedMs(started));
            return prepared;
        }
        ToolResult trial = sqlTools.trial(
                new IndicatorSqlTools.TrialInput(text(prepared.data().get("sql_id"))),
                execution.context);
        Map<String, Object> layer = new LinkedHashMap<>(layer(
                3, "执行当前口径", trial.ok() ? "passed" : "failed",
                !trial.ok(), false,
                List.of(check(trial.code(), trial.ok() ? "pass" : "fail", trial.summary())),
                elapsedMs(started)));
        layer.put("sql_id", prepared.data().get("sql_id"));
        if (trial.ok()) {
            layer.put("run_id", trial.data().get("run_id"));
            layer.put("result_value", trial.data().get("result_value"));
            layer.put("numerator_count", trial.data().get("numerator_count"));
            layer.put("denominator_count", trial.data().get("denominator_count"));
            execution.baseline = trial;
            execution.baselineSqlId = text(prepared.data().get("sql_id"));
            execution.baselineRunId = text(trial.data().get("run_id"));
        }
        execution.baselineLayer = Map.copyOf(layer);
        return trial;
    }

    private ToolResult analyzeUploadAgainst(ToolResult trial, DiagnosticExecution execution) {
        AgentRunState state = execution.context.runState();
        state.lastToolResults().add(trial);
        try {
            return uploadTools.analyze(
                    new UploadedIndicatorTools.Input(execution.input.fileKey()), execution.context);
        } finally {
            state.lastToolResults().remove(state.lastToolResults().size() - 1);
            state.lastRunId(execution.baselineRunId);
        }
    }

    private Map<String, Object> periodConflict(
            DiagnosticExecution execution,
            ToolResult fileEvidence) {
        if (fileEvidence == null) return Map.of();
        String uploadedPeriod = text(fileEvidence.data().get("uploaded_stat_period"));
        if (uploadedPeriod.isBlank()) return Map.of();
        List<LocalDate> dates = periodDates(uploadedPeriod);
        if (dates.size() < 2) return Map.of();
        LocalDate expectedStart = execution.start.toLocalDate();
        LocalDate expectedEnd = execution.end.toLocalDate();
        // 只取统计区间文本中前两个日期作为端点。括号中的“覆盖至某日”等说明日期
        // 不能参与匹配，否则完整自然日 [7月23日, 7月24日) 会被误认为“到当前时刻”。
        boolean matches = dates.get(0).equals(expectedStart)
                && (dates.get(1).equals(expectedEnd)
                        || dates.get(1).equals(expectedEnd.minusDays(1)));
        if (matches) return Map.of();
        return layer(1, "诊断范围预检", "blocked", true, false,
                List.of(check("FILE_PERIOD_CONFLICT", "fail",
                        "上传文件统计区间 " + uploadedPeriod + " 与本轮区间 "
                                + execution.input.statStartTime() + " 至 "
                                + execution.input.statEndTime() + " 不一致。")),
                0);
    }

    private Map<String, Object> caliberLayer(DiagnosticExecution execution) {
        long started = System.nanoTime();
        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Map<String, Object>> profiles = rules.diagnosticProfiles(
                execution.input.ruleId(), execution.context.agentContext().hospitalId()).stream()
                .filter(profile -> appliesToPeriod(profile, execution.start.toLocalDate(), execution.end.toLocalDate()))
                .filter(profile -> !Boolean.TRUE.equals(profile.get("baseline_equivalent")))
                .sorted(Comparator.comparing(profile -> caliberPriority(text(profile.get("source_level")))))
                .limit(MAX_CALIBER_CANDIDATES)
                .toList();

        boolean causeConfirmed = false;
        boolean causeLikely = false;
        String conclusion = "";
        String likelyConclusion = "";
        for (Map<String, Object> profile : profiles) {
            Map<String, Object> candidate = runCandidate(profile, execution);
            candidates.add(candidate);
            if (Boolean.TRUE.equals(candidate.get("cause_confirmed"))) {
                causeConfirmed = true;
                conclusion = "已确认用户结果与候选口径“"
                        + text(profile.get("label")) + "”一致，且用户描述或逐条记录证据支持该口径差异。";
                break;
            }
            if (!causeLikely && Boolean.TRUE.equals(candidate.get("cause_likely"))) {
                causeLikely = true;
                likelyConclusion = "候选口径“" + text(profile.get("label"))
                        + "”与用户结果部分一致，且口径描述或文件字段支持该方向；"
                        + "仍有" + dimensionLabels(strings(candidate.get("mismatched_dimensions")))
                        + "差异需要继续核对。";
            }
        }

        Map<String, Object> result = new LinkedHashMap<>(layer(
                4, "试运行候选口径",
                causeConfirmed ? "confirmed" : causeLikely ? "likely" : "completed",
                false, causeConfirmed, List.of(), elapsedMs(started)));
        result.put("candidate_limit", MAX_CALIBER_CANDIDATES);
        result.put("candidate_count", candidates.size());
        result.put("candidates", candidates);
        result.put("cause_confirmed", causeConfirmed);
        result.put("cause_likely", causeLikely);
        if (!conclusion.isBlank()) result.put("conclusion", conclusion);
        if (!likelyConclusion.isBlank()) result.put("likely_conclusion", likelyConclusion);
        return Map.copyOf(result);
    }

    private Map<String, Object> runCandidate(
            Map<String, Object> profile,
            DiagnosticExecution execution) {
        Map<String, Object> result = new LinkedHashMap<>();
        String profileId = text(profile.get("profile_id"));
        result.put("profile_id", profileId);
        result.put("label", profile.getOrDefault("label", profileId));
        result.put("source_level", profile.getOrDefault("source_level", ""));
        result.put("source_version", profile.getOrDefault("source_version", ""));
        result.put("difference_dimensions", profile.getOrDefault("difference_dimensions", List.of()));

        ToolResult prepared = sqlTools.prepareDiagnostic(
                new IndicatorSqlTools.PrepareInput(
                        execution.input.ruleId(),
                        execution.input.statStartTime(),
                        execution.input.statEndTime()),
                profileId,
                objectMap(profile.get("parameter_overrides")),
                objectMap(profile.get("field_role_overrides")),
                execution.context);
        if (!prepared.ok()) {
            result.put("executable", false);
            result.put("status", prepared.code());
            result.put("message", prepared.summary());
            return Map.copyOf(result);
        }
        ToolResult trial = sqlTools.trial(
                new IndicatorSqlTools.TrialInput(text(prepared.data().get("sql_id"))),
                execution.context);
        if (!trial.ok()) {
            result.put("executable", true);
            result.put("status", trial.code());
            result.put("message", trial.summary());
            return Map.copyOf(result);
        }
        result.put("executable", true);
        result.put("status", "completed");
        result.put("sql_id", prepared.data().get("sql_id"));
        result.put("run_id", trial.data().get("run_id"));
        result.put("result_value", trial.data().get("result_value"));
        result.put("numerator_count", trial.data().get("numerator_count"));
        result.put("denominator_count", trial.data().get("denominator_count"));

        CandidateMatch match = compareCandidateToExternal(trial.data(), execution);
        boolean differsFromBaseline = differs(trial.data(), execution.baseline.data());
        boolean keywordEvidence = keywordsMatch(
                profile.get("evidence_keywords"), execution.input.issueDescription());
        boolean fileSchemaEvidence = execution.initialUploadInspection != null
                && keywordsMatch(
                        profile.get("evidence_keywords"),
                        String.valueOf(execution.initialUploadInspection.data().getOrDefault(
                                "columns", List.of())));
        boolean rowEvidence = false;
        if (execution.input.fileKey() != null && detailExport(execution.initialUploadInspection)) {
            ToolResult comparison = analyzeUploadAgainst(trial, execution);
            rowEvidence = rowSetsEqual(comparison.data());
            result.put("row_evidence", safeRowSummary(comparison.data()));
        }
        boolean semanticEvidence = keywordEvidence || fileSchemaEvidence;
        boolean confirmed = differsFromBaseline
                && ("exact".equals(match.level()) || rowEvidence);
        // 上传汇总中的分子、分母、指标率是有明确字段含义的证据。只要其中部分维度
        // 匹配，且用户描述或文件表头明确指向该候选口径，就标记为“高度相关”；
        // 普通对话中一个没有维度标签的数字仍只能算“可能相关”。
        boolean structuredExternalEvidence = !"user_statement".equals(
                text(safeExternal(execution).get("source")));
        boolean likely = !confirmed
                && differsFromBaseline
                && "partial".equals(match.level())
                && structuredExternalEvidence
                && semanticEvidence;
        String causeLikelihood = confirmed
                ? "confirmed"
                : likely
                        ? "likely"
                        : "partial".equals(match.level()) ? "possible" : "none";
        result.put("match_level", match.level());
        result.put("matching_dimensions", match.matchingDimensions());
        result.put("mismatched_dimensions", match.mismatchedDimensions());
        result.put("metric_differences", match.metricDifferences());
        result.put("external_result_match", "exact".equals(match.level()));
        result.put("external_partial_match", "partial".equals(match.level()));
        result.put("differs_from_baseline", differsFromBaseline);
        result.put("keyword_evidence", keywordEvidence);
        result.put("file_schema_evidence", fileSchemaEvidence);
        result.put("row_evidence_confirmed", rowEvidence);
        result.put("cause_confirmed", confirmed);
        result.put("cause_likely", likely);
        result.put("cause_likelihood", causeLikelihood);
        if (likely) {
            result.put("evidence_limit",
                    "候选口径与外部结果部分一致，且存在口径语义证据；"
                            + "未匹配维度仍需通过逐条记录或数据质量检查解释。");
        } else if ("partial".equals(match.level())) {
            result.put("evidence_limit",
                    "候选结果只命中部分数值，且证据不足；不能仅凭单个或未标注数值相同确认原因。");
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> recordSetLayer(DiagnosticExecution execution) {
        long started = System.nanoTime();
        ToolResult comparison = execution.uploadComparison;
        if (comparison == null) {
            return layer(5, "核对记录集合", "skipped", false, false,
                    List.of(check("NO_UPLOAD_FILE", "info",
                            "未提供逐条文件，不能判断用户侧具体记录的交集与差集。")),
                    elapsedMs(started));
        }
        Map<String, Object> data = comparison.data();
        if (!rowLevelAvailable(comparison)) {
            return layer(5, "核对记录集合", "insufficient", false, false,
                    List.of(check("SUMMARY_ONLY_FILE", "info",
                            "上传文件仅能进行汇总比较，缺少可关联的逐条业务标识。")),
                    elapsedMs(started));
        }

        long both = longValue(data.get("both_count"));
        long systemOnly = longValue(data.get("system_only_count"));
        long uploadedOnly = longValue(data.get("uploaded_only_count"));
        long fieldDiff = longValue(data.get("field_difference_count"));
        long decisionDiff = longValue(data.get("decision_difference_count"));
        // 字段差异和达标判定差异可能发生在同一条已匹配记录上，使用最大值避免重复计数。
        long differences = systemOnly + uploadedOnly + Math.max(fieldDiff, decisionDiff);
        List<String> findings = strings(data.get("confirmed_findings"));
        boolean confirmed = differences > 0;
        boolean fullyExplained = recordDifferencesReconcileAggregates(data);
        Map<String, Object> result = new LinkedHashMap<>(layer(
                5, "核对记录集合", confirmed ? "confirmed" : "passed",
                false, confirmed, List.of(), elapsedMs(started)));
        result.put("both_count", both);
        result.put("system_only_count", systemOnly);
        result.put("uploaded_only_count", uploadedOnly);
        result.put("field_difference_count", fieldDiff);
        result.put("decision_difference_count", decisionDiff);
        result.put("affected_record_count", differences);
        result.put("confirmed_findings", findings);
        result.put("cause_confirmed", confirmed);
        result.put("difference_fully_explained", confirmed && fullyExplained);
        result.put("conclusion", confirmed
                ? "已通过逐条标识确认双方记录集合或达标判定存在差异，共影响至少 "
                        + differences + " 条记录"
                        + (fullyExplained ? "，且汇总差值可由这些逐条差异完整复算。"
                                : "；当前层只解释部分差值，将继续检查数据质量。")
                : "逐条核对未发现记录集合差异。");
        return Map.copyOf(result);
    }

    /**
     * 用集合差和达标分类差重新计算汇总差值，防止仅凭“发现若干不同记录”就提前短路。
     */
    private static boolean recordDifferencesReconcileAggregates(Map<String, Object> data) {
        long systemCount = longValue(data.get("system_count"));
        long uploadedCount = longValue(data.get("uploaded_count"));
        long systemOnly = longValue(data.get("system_only_count"));
        long uploadedOnly = longValue(data.get("uploaded_only_count"));
        boolean denominatorExplained =
                systemCount - uploadedCount == systemOnly - uploadedOnly;

        long systemNumerator = longValue(data.get("system_numerator_count"));
        long uploadedNumerator = longValue(data.get("uploaded_numerator_count"));
        long systemOnlyNumerator = longValue(data.get("system_only_numerator_count"));
        long uploadedOnlyNumerator = longValue(data.get("uploaded_only_numerator_count"));
        long classificationDiff = longValue(data.get("classification_difference_count"));
        // 没有分类方向时，只能在不存在分类差异时证明分子差值被单边记录完整解释。
        boolean numeratorExplained = classificationDiff == 0
                && systemNumerator - uploadedNumerator
                        == systemOnlyNumerator - uploadedOnlyNumerator;
        return denominatorExplained && numeratorExplained;
    }

    /**
     * 质量规则解释器仅支持三类允许列表检查。业务字段先解析为 Wiki 中已确认的物理字段，
     * 再由服务端生成 COUNT 查询；规则中出现未知类型或未知字段时只报告不可执行，不拼接 SQL。
     */
    private Map<String, Object> qualityLayer(DiagnosticExecution execution) {
        long started = System.nanoTime();
        List<Map<String, Object>> checks = new ArrayList<>();
        long affected = 0;
        for (Map<String, Object> qualityRule : rules.dataQualityRules(execution.input.ruleId())) {
            Map<String, Object> checkResult = executeQualityRule(qualityRule, execution);
            checks.add(checkResult);
            if ("confirmed".equals(checkResult.get("status"))) {
                affected += longValue(checkResult.get("affected_count"));
            }
        }
        boolean hasAnomaly = affected > 0;
        boolean linkedToDifference = hasAnomaly && qualityEvidenceLinksToRows(execution.uploadComparison);
        Map<String, Object> result = new LinkedHashMap<>(layer(
                6, "检查数据质量", hasAnomaly ? "warning" : "passed",
                false, linkedToDifference, checks, elapsedMs(started)));
        result.put("quality_rule_count", checks.size());
        result.put("affected_record_count", affected);
        result.put("cause_confirmed", linkedToDifference);
        result.put("conclusion", linkedToDifference
                ? "已确认逐条差异与受控数据质量异常相交，共影响 " + affected + " 条记录。"
                : hasAnomaly
                        ? "系统数据存在质量异常，但当前证据不能证明它就是用户结果差异的原因。"
                        : "允许列表数据质量检查未发现异常。");
        return Map.copyOf(result);
    }

    private Map<String, Object> executeQualityRule(
            Map<String, Object> qualityRule,
            DiagnosticExecution execution) {
        String id = text(qualityRule.get("id"));
        String type = text(qualityRule.get("type"));
        String sql;
        try {
            sql = switch (type) {
                case "required_not_null" -> requiredNotNullSql(strings(qualityRule.get("fields")), execution);
                case "duplicate_key" -> duplicateKeySql(strings(qualityRule.get("fields")), execution);
                case "timestamp_order" -> timestampOrderSql(
                        text(qualityRule.get("earlier_field")),
                        text(qualityRule.get("later_field")),
                        Boolean.TRUE.equals(qualityRule.get("allow_later_null")),
                        execution);
                default -> "";
            };
        } catch (IllegalArgumentException exception) {
            sql = "";
        }
        if (sql.isBlank()) {
            return Map.of(
                    "check_id", id,
                    "type", type,
                    "status", "not_executable",
                    "message", "质量规则包含未知类型、未知字段或缺少安全关联，未执行。");
        }
        try {
            List<Map<String, Object>> rows = businessQuery.execute(sql);
            long count = rows.isEmpty() ? 0 : longValue(valueIgnoreCaseObject(rows.get(0), "issue_count"));
            return Map.of(
                    "check_id", id,
                    "type", type,
                    "status", count > 0 ? "confirmed" : "passed",
                    "affected_count", count,
                    "description", text(qualityRule.get("description")));
        } catch (RuntimeException exception) {
            return Map.of(
                    "check_id", id,
                    "type", type,
                    "status", "failed",
                    "message", "质量检查无法通过 DBHub 完成。");
        }
    }

    private String requiredNotNullSql(List<String> fields, DiagnosticExecution execution) {
        if (fields.isEmpty()) return "";
        QueryScope scope = queryScope(execution, fields);
        List<String> conditions = new ArrayList<>();
        for (String field : fields) {
            PhysicalField ref = requireField(field, execution.mapping);
            conditions.add(qualified(ref, execution.mapping) + " IS NULL");
        }
        return countSql(scope, "(" + String.join(" OR ", conditions) + ")", execution);
    }

    private String duplicateKeySql(List<String> fields, DiagnosticExecution execution) {
        if (fields.isEmpty()) return "";
        QueryScope scope = queryScope(execution, fields);
        List<String> keys = fields.stream()
                .map(field -> qualified(requireField(field, execution.mapping), execution.mapping))
                .toList();
        return "SELECT COUNT(*) AS issue_count FROM (SELECT " + String.join(", ", keys)
                + " " + scope.fromClause() + " WHERE " + scope.scopeWhere()
                + " GROUP BY " + String.join(", ", keys) + " HAVING COUNT(*) > 1) AS quality_duplicates";
    }

    private String timestampOrderSql(
            String earlierField,
            String laterField,
            boolean allowLaterNull,
            DiagnosticExecution execution) {
        if (earlierField.isBlank() || laterField.isBlank()) return "";
        QueryScope scope = queryScope(execution, List.of(earlierField, laterField));
        String earlier = qualified(requireField(earlierField, execution.mapping), execution.mapping);
        String later = qualified(requireField(laterField, execution.mapping), execution.mapping);
        String condition = later + " < " + earlier;
        if (!allowLaterNull) condition = "(" + later + " IS NULL OR " + condition + ")";
        return countSql(scope, condition, execution);
    }

    private String countSql(QueryScope scope, String condition, DiagnosticExecution execution) {
        String function = "sqlserver".equalsIgnoreCase(text(execution.mapping.get("dialect")))
                ? "COUNT_BIG" : "COUNT";
        return "SELECT " + function + "(*) AS issue_count " + scope.fromClause()
                + " WHERE " + scope.scopeWhere() + " AND " + condition;
    }

    private QueryScope queryScope(DiagnosticExecution execution, Collection<String> checkedFields) {
        PhysicalField main = new PhysicalField(
                text(execution.mapping.get("main_table")), "");
        if (!safeIdentifier(main.table())) throw new IllegalArgumentException("主表无效");
        Set<String> tables = new LinkedHashSet<>();
        tables.add(main.table());
        for (String field : checkedFields) {
            tables.add(requireField(field, execution.mapping).table());
        }
        String dialect = text(execution.mapping.get("dialect"));
        String schema = text(execution.mapping.get("schema"));
        StringBuilder from = new StringBuilder("FROM ")
                .append(qualifiedTable(main.table(), schema, dialect))
                .append(" AS ").append(quote(main.table(), dialect));
        for (String table : tables) {
            if (table.equalsIgnoreCase(main.table())) continue;
            Map<String, Object> relation = findRelation(main.table(), table, execution.mapping);
            if (relation.isEmpty()) throw new IllegalArgumentException("缺少关联");
            String leftTable = text(relation.get("left_table"));
            String rightTable = text(relation.get("right_table"));
            String other = main.table().equalsIgnoreCase(leftTable) ? rightTable : leftTable;
            String mainColumn = main.table().equalsIgnoreCase(leftTable)
                    ? text(relation.get("left_column")) : text(relation.get("right_column"));
            String otherColumn = main.table().equalsIgnoreCase(leftTable)
                    ? text(relation.get("right_column")) : text(relation.get("left_column"));
            if (!safeIdentifier(other) || !safeIdentifier(mainColumn) || !safeIdentifier(otherColumn)) {
                throw new IllegalArgumentException("关联标识无效");
            }
            from.append(" LEFT JOIN ").append(qualifiedTable(other, schema, dialect))
                    .append(" AS ").append(quote(other, dialect))
                    .append(" ON ").append(quote(main.table(), dialect)).append(".").append(quote(mainColumn, dialect))
                    .append(" = ").append(quote(other, dialect)).append(".").append(quote(otherColumn, dialect));
        }

        PhysicalField hospital = requireField("hospital_id", execution.mapping);
        PhysicalField period = requireField(periodBusinessField(execution.rule), execution.mapping);
        Map<String, Object> params = objectMap(execution.rule.get("effective_params"));
        Object hospitalValue = firstPresent(
                params.get("hospital_soid"),
                params.get("hospital_id"),
                execution.context.agentContext().hospitalId());
        String where = qualified(hospital, execution.mapping) + " = " + literal(hospitalValue)
                + " AND " + qualified(period, execution.mapping) + " >= " + literal(execution.input.statStartTime())
                + " AND " + qualified(period, execution.mapping) + " < " + literal(execution.input.statEndTime());
        return new QueryScope(from.toString(), where);
    }

    private static void addLayer(
            DiagnosticExecution execution,
            Map<String, Object> layer) {
        execution.layers.add(layer);
        int number = layer.get("layer") instanceof Number value ? value.intValue() : 0;
        String status = text(layer.get("status"));
        String traceStatus = switch (status) {
            case "blocked", "failed" -> "failed";
            case "warning", "insufficient", "likely" -> "warning";
            default -> "success";
        };
        execution.context.runState().reportProgress(new AgentRunState.WorkflowProgress(
                "difference_diagnosis_layer_" + number,
                text(layer.get("node_name")),
                traceStatus,
                longValue(layer.get("duration_ms")),
                layer));
    }

    private ToolResult finish(
            DiagnosticExecution execution,
            String conclusionCode,
            int stoppedLayer,
            String conclusion) {
        String reportId = id("DDR_");
        Map<String, Object> baseline = execution.baseline == null
                ? Map.of()
                : safeTrial(execution.baseline.data());
        Map<String, Object> external = safeExternal(execution);
        // 不同层的异常集合可能重叠。报告没有患者级集合交集时取最大确认值，不能相加后
        // 夸大影响记录数。
        long affected = execution.layers.stream()
                .mapToLong(layer -> longValue(layer.get("affected_record_count")))
                .max()
                .orElse(0);
        List<String> confirmedFindings = execution.layers.stream()
                .flatMap(layer -> strings(layer.get("confirmed_findings")).stream())
                .distinct()
                .toList();
        Map<String, Object> caliberLayer = execution.layers.stream()
                .filter(layer -> Integer.valueOf(4).equals(layer.get("layer")))
                .findFirst()
                .orElse(Map.of());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report_schema_version", "difference-diagnosis-report-v2");
        report.put("report_id", reportId);
        report.put("rule_id", execution.input.ruleId());
        report.put("hospital_id", execution.context.agentContext().hospitalId());
        report.put("stat_start", execution.input.statStartTime());
        report.put("stat_end", execution.input.statEndTime());
        report.put("conclusion_code", conclusionCode);
        report.put("diagnose_status", conclusionStatus(conclusionCode));
        report.put("stopped_layer", stoppedLayer);
        report.put("user_summary", conclusion);
        report.put("cause_confirmed", conclusionCode.endsWith("_CAUSE_CONFIRMED")
                || "RECORD_SET_DIFF_CONFIRMED".equals(conclusionCode));
        report.put("affected_record_count", affected);
        report.put("confirmed_findings", confirmedFindings);
        report.put("baseline_result", baseline);
        report.put("external_evidence", external);
        // 提升到报告顶层，供最终回答和前端直接展示候选试算，不需要解析内部层级结构。
        report.put("caliber_candidates", listOfMaps(caliberLayer.get("candidates")));
        report.put("caliber_cause_likely",
                Boolean.TRUE.equals(caliberLayer.get("cause_likely")));
        report.put("file_key", execution.input.fileKey() == null ? "" : execution.input.fileKey());
        report.put("baseline_run_id", execution.baselineRunId == null ? "" : execution.baselineRunId);
        report.put("baseline_sql_id", execution.baselineSqlId == null ? "" : execution.baselineSqlId);
        report.put("layers", List.copyOf(execution.layers));
        report.put("evidence_limit",
                "未发现系统异常时仅表示当前证据下系统结果内部一致，不代表用户结果必然错误。");

        try {
            reports.saveDifference(
                    reportId,
                    execution.context.agentContext().hospitalId(),
                    execution.input.ruleId(),
                    conclusion,
                    repairSuggestion(conclusionCode),
                    report,
                    conclusionStatus(conclusionCode),
                    execution.input.statStartTime() + " 至 " + execution.input.statEndTime(),
                    execution.baselineSqlId);
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    "error", "DIAGNOSIS_REPORT_SAVE_FAILED",
                    "差异诊断已执行，但报告保存失败。", false);
        }
        execution.context.runState().lastRunId(execution.baselineRunId);
        if (execution.baselineSqlId != null) {
            execution.context.runState().validatedSqlIds().remove(execution.baselineSqlId);
            execution.context.runState().validatedSqlIds().add(execution.baselineSqlId);
        }
        execution.context.runState().lastDiagnosisId(reportId);
        execution.context.runState().reportProgress(new AgentRunState.WorkflowProgress(
                "difference_diagnosis_conclusion",
                "生成诊断结论",
                "success",
                0,
                Map.of(
                        "report_id", reportId,
                        "conclusion_code", conclusionCode,
                        "stopped_layer", stoppedLayer,
                        "user_summary", conclusion)));
        return ToolResult.success(
                "DIFFERENCE_DIAGNOSIS_COMPLETED",
                "指标差异分层诊断已完成。",
                Map.copyOf(report));
    }

    private static String conclusionStatus(String code) {
        return switch (code) {
            case "STRUCTURE_BLOCKING" -> "blocked";
            case "INSUFFICIENT_EXTERNAL_EVIDENCE" -> "insufficient";
            case "SYSTEM_RESULT_VERIFIED" -> "verified";
            case "CALIBER_CAUSE_LIKELY" -> "likely";
            default -> "confirmed";
        };
    }

    private static String repairSuggestion(String code) {
        return switch (code) {
            case "STRUCTURE_BLOCKING" -> "先修复字段、表、类型或关联映射后重新诊断。";
            case "STRUCTURE_CAUSE_CONFIRMED" -> "修复已确认的字段、关联或医院映射差异后重新计算。";
            case "CALIBER_CAUSE_CONFIRMED" -> "统一指标版本、阈值、排除条件和统计周期后重新计算。";
            case "CALIBER_CAUSE_LIKELY" -> "候选口径高度相关；继续核对未匹配的分子、分母或逐条记录后再确认。";
            case "RECORD_SET_DIFF_CONFIRMED" -> "导出受权限保护的差异明细，逐条核对单边记录和达标判定。";
            case "DATA_QUALITY_CAUSE_CONFIRMED" -> "按异常类型治理源数据后重新计算。";
            case "INSUFFICIENT_EXTERNAL_EVIDENCE" -> "补充包含稳定业务主键和统计区间的逐条明细。";
            default -> "保留本次 Evidence；若仍有争议，请补充用户侧逐条明细。";
        };
    }

    private static Map<String, Object> layer(
            int number,
            String name,
            String status,
            boolean blocking,
            boolean causeConfirmed,
            List<Map<String, Object>> checks,
            long durationMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("layer", number);
        result.put("node_name", name);
        result.put("status", status);
        result.put("blocking", blocking);
        result.put("cause_confirmed", causeConfirmed);
        result.put("checks", checks);
        result.put("duration_ms", durationMs);
        return Map.copyOf(result);
    }

    private static Map<String, Object> check(String code, String status, String message) {
        return Map.of("code", code, "status", status, "message", message);
    }

    private static Map<String, Object> claimedValues(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        Matcher user = USER_VALUE.matcher(text == null ? "" : text);
        Matcher system = SYSTEM_VALUE.matcher(text == null ? "" : text);
        if (user.find()) result.put("user_value", number(user.group(1)));
        if (system.find()) result.put("system_value", number(system.group(1)));
        return Map.copyOf(result);
    }

    /**
     * 按“分子、分母、指标率”三个业务维度逐项比较候选口径和外部结果。
     *
     * <p>完整匹配要求外部证据至少明确给出分子和分母，且所有已提供维度都一致。
     * 仅在自然语言中出现的单个数值没有维度标签，即使碰巧命中候选结果，也只能标记为
     * partial，防止把偶然相等误判为已确认原因。</p>
     */
    private static CandidateMatch compareCandidateToExternal(
            Map<String, Object> candidate,
            DiagnosticExecution execution) {
        Map<String, Object> external = safeExternal(execution);
        List<String> matching = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        List<Map<String, Object>> differences = new ArrayList<>();
        if (external.containsKey("numerator") || external.containsKey("denominator")
                || external.containsKey("rate")) {
            Map<String, String> dimensions = new LinkedHashMap<>();
            dimensions.put("numerator", "numerator_count");
            dimensions.put("denominator", "denominator_count");
            dimensions.put("rate", "result_value");
            for (Map.Entry<String, String> metric : dimensions.entrySet()) {
                if (!external.containsKey(metric.getKey())) continue;
                Object candidateValue = candidate.get(metric.getValue());
                Object externalValue = external.get(metric.getKey());
                if (metricMatches(candidateValue, externalValue)) {
                    matching.add(metric.getKey());
                } else {
                    mismatched.add(metric.getKey());
                    differences.add(metricDifference(
                            metric.getKey(), candidateValue, externalValue));
                }
            }
            boolean hasCountPair = external.containsKey("numerator")
                    && external.containsKey("denominator");
            String level = hasCountPair && !matching.isEmpty() && mismatched.isEmpty()
                    ? "exact"
                    : !matching.isEmpty() ? "partial" : "none";
            return new CandidateMatch(
                    level, List.copyOf(matching), List.copyOf(mismatched), List.copyOf(differences));
        }
        Object claim = external.get("claimed_user_value");
        if (claim != null) {
            Map<String, Object> dimensions = new LinkedHashMap<>();
            dimensions.put("numerator", candidate.get("numerator_count"));
            dimensions.put("denominator", candidate.get("denominator_count"));
            dimensions.put("rate", candidate.get("result_value"));
            for (Map.Entry<String, Object> metric : dimensions.entrySet()) {
                if (metric.getValue() != null && same(metric.getValue(), claim)) {
                    matching.add(metric.getKey());
                }
            }
        }
        return new CandidateMatch(
                matching.isEmpty() ? "none" : "partial",
                List.copyOf(matching),
                List.of(),
                List.of());
    }

    private static Map<String, Object> metricDifference(
            String dimension,
            Object candidateValue,
            Object externalValue) {
        Map<String, Object> difference = new LinkedHashMap<>();
        difference.put("dimension", dimension);
        difference.put("candidate_value", candidateValue == null ? "" : candidateValue);
        difference.put("external_value", externalValue == null ? "" : externalValue);
        if (candidateValue != null && externalValue != null) {
            difference.put("delta", BigDecimal.valueOf(
                            doubleValue(candidateValue) - doubleValue(externalValue))
                    .setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString());
        } else {
            difference.put("delta", "");
        }
        return Map.copyOf(difference);
    }

    private static String dimensionLabels(List<String> dimensions) {
        if (dimensions.isEmpty()) return "尚未定位的维度";
        return dimensions.stream()
                .map(IndicatorDifferenceDiagnosisWorkflow::dimensionLabel)
                .distinct()
                .reduce((left, right) -> left + "、" + right)
                .orElse("尚未定位的维度");
    }

    private static String dimensionLabel(String dimension) {
        return switch (dimension) {
            case "numerator" -> "分子";
            case "denominator" -> "分母";
            case "rate" -> "指标率";
            default -> dimension;
        };
    }

    /**
     * 外部证据可能只提供其中一个指标值。没有提供的维度不参与匹配，但至少需要一个维度。
     */
    private static boolean metricMatches(Object candidate, Object external) {
        if (external == null) return true;
        if (candidate == null) return false;
        return Math.abs(doubleValue(candidate) - doubleValue(external)) < 0.01;
    }

    private record CandidateMatch(
            String level,
            List<String> matchingDimensions,
            List<String> mismatchedDimensions,
            List<Map<String, Object>> metricDifferences) {}

    private static boolean differs(Map<String, Object> left, Map<String, Object> right) {
        return !same(left.get("numerator_count"), right.get("numerator_count"))
                || !same(left.get("denominator_count"), right.get("denominator_count"))
                || !same(left.get("result_value"), right.get("result_value"));
    }

    private static boolean same(Object left, Object right) {
        if (left == null || right == null) return left == right;
        return Math.abs(doubleValue(left) - doubleValue(right)) < 0.01;
    }

    private static boolean hasExternalValues(DiagnosticExecution execution) {
        return !safeExternal(execution).isEmpty();
    }

    private static Map<String, Object> safeExternal(DiagnosticExecution execution) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (execution.uploadComparison != null) {
            Object numerator = firstPresent(
                    execution.uploadComparison.data().get("uploaded_numerator"),
                    execution.uploadComparison.data().get("uploaded_numerator_count"));
            Object denominator = firstPresent(
                    execution.uploadComparison.data().get("uploaded_denominator"),
                    execution.uploadComparison.data().get("uploaded_count"));
            Object rate = firstPresent(
                    execution.uploadComparison.data().get("uploaded_rate"));
            if (text(rate).isBlank()
                    && !text(numerator).isBlank()
                    && !text(denominator).isBlank()
                    && doubleValue(denominator) != 0) {
                rate = BigDecimal.valueOf(
                                doubleValue(numerator) * 100.0 / doubleValue(denominator))
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();
            }
            put(result, "numerator", numerator);
            put(result, "denominator", denominator);
            put(result, "rate", rate);
            put(result, "stat_period", execution.uploadComparison.data().get("uploaded_stat_period"));
            if (!result.isEmpty()) result.put("source", "uploaded_file");
        } else {
            Map<String, Object> claims = claimedValues(execution.input.issueDescription());
            put(result, "claimed_user_value", claims.get("user_value"));
            if (!claims.isEmpty()) result.put("source", "user_statement");
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> safeTrial(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of(
                "run_id", "sql_id", "rule_id", "stat_start", "stat_end",
                "result_value", "numerator_count", "denominator_count", "source")) {
            put(result, key, source.get(key));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> safeRowSummary(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of(
                "row_level_comparison_available", "both_count", "system_only_count",
                "uploaded_only_count", "field_difference_count", "decision_difference_count")) {
            put(result, key, data.get(key));
        }
        return Map.copyOf(result);
    }

    private static boolean rowLevelAvailable(ToolResult comparison) {
        return comparison != null
                && Boolean.TRUE.equals(comparison.data().get("row_level_comparison_available"));
    }

    private static boolean rowSetsEqual(Map<String, Object> data) {
        return Boolean.TRUE.equals(data.get("row_level_comparison_available"))
                && longValue(data.get("system_only_count")) == 0
                && longValue(data.get("uploaded_only_count")) == 0
                && longValue(data.get("field_difference_count")) == 0
                && longValue(data.get("decision_difference_count")) == 0;
    }

    private static boolean detailExport(ToolResult inspection) {
        return inspection != null
                && (Boolean.TRUE.equals(inspection.data().get("contains_detail_records"))
                        || Boolean.TRUE.equals(inspection.data().get("row_level_comparison_available"))
                        || "detail".equals(inspection.data().get("comparison_level")));
    }

    private static boolean qualityEvidenceLinksToRows(ToolResult comparison) {
        if (!rowLevelAvailable(comparison)) return false;
        // 当前上传比较结果只提供记录集合与字段差异汇总，没有质量异常行和差异行的
        // 结构化交集对象。文字中出现“重复/空值”等词不能作为因果证据，因此保守返回 false。
        return Boolean.TRUE.equals(comparison.data().get("quality_difference_intersection_confirmed"));
    }

    private static boolean keywordsMatch(Object values, String issue) {
        String normalized = issue == null ? "" : issue.toLowerCase(Locale.ROOT);
        return strings(values).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private static boolean appliesToPeriod(
            Map<String, Object> profile,
            LocalDate start,
            LocalDate endExclusive) {
        try {
            String from = text(profile.get("effective_from"));
            String to = text(profile.get("effective_to"));
            LocalDate effectiveFrom = from.isBlank() ? LocalDate.MIN : LocalDate.parse(from);
            LocalDate effectiveTo = to.isBlank() ? LocalDate.MAX : LocalDate.parse(to);
            return effectiveFrom.isBefore(endExclusive) && !effectiveTo.isBefore(start);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static int caliberPriority(String sourceLevel) {
        return switch (sourceLevel.toLowerCase(Locale.ROOT)) {
            case "hospital_history" -> 0;
            case "company" -> 1;
            case "national" -> 2;
            default -> 3;
        };
    }

    private static String periodBusinessField(Map<String, Object> rule) {
        for (Map<String, Object> condition : listOfMaps(
                objectMap(objectMap(rule.get("calculation_definition")).get("scope")).get("conditions"))) {
            if ("half_open_range".equals(text(condition.get("operator")))) {
                return text(condition.get("field"));
            }
        }
        return "admit_time";
    }

    private static String deduplicationField(Map<String, Object> rule) {
        Map<String, Object> denominator = objectMap(
                objectMap(rule.get("calculation_definition")).get("denominator"));
        return text(objectMap(denominator.get("aggregate")).get("field"));
    }

    private static PhysicalField requireField(String businessField, Map<String, Object> mapping) {
        PhysicalField value = physicalField(objectMap(mapping.get("fields")).get(businessField));
        if (value == null) throw new IllegalArgumentException("未知业务字段");
        return value;
    }

    private static PhysicalField physicalField(Object value) {
        String[] parts = text(value).split("\\.");
        if (parts.length < 2) return null;
        String table = parts[parts.length - 2];
        String column = parts[parts.length - 1];
        return safeIdentifier(table) && safeIdentifier(column)
                ? new PhysicalField(table, column) : null;
    }

    private static String qualified(PhysicalField field, Map<String, Object> mapping) {
        String dialect = text(mapping.get("dialect"));
        return quote(field.table(), dialect) + "." + quote(field.column(), dialect);
    }

    private static String qualifiedTable(String table, String schema, String dialect) {
        if (!safeIdentifier(table) || (!schema.isBlank() && !safeIdentifier(schema))) {
            throw new IllegalArgumentException("数据库对象标识无效");
        }
        return schema.isBlank() ? quote(table, dialect)
                : quote(schema, dialect) + "." + quote(table, dialect);
    }

    private static String quote(String value, String dialect) {
        return "sqlserver".equalsIgnoreCase(dialect) ? "[" + value + "]" : "`" + value + "`";
    }

    private static String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "'" + text(value).replace("'", "''") + "'";
    }

    private static boolean safeIdentifier(String value) {
        return value != null && SAFE_IDENTIFIER.matcher(value).matches();
    }

    private static Map<String, Object> findRelation(
            String mainTable,
            String otherTable,
            Map<String, Object> mapping) {
        return listOfMaps(mapping.get("relations")).stream()
                .filter(item -> "confirmed".equalsIgnoreCase(text(item.getOrDefault("status", "confirmed"))))
                .filter(item -> {
                    String left = text(item.get("left_table"));
                    String right = text(item.get("right_table"));
                    return mainTable.equalsIgnoreCase(left) && otherTable.equalsIgnoreCase(right)
                            || mainTable.equalsIgnoreCase(right) && otherTable.equalsIgnoreCase(left);
                })
                .findFirst()
                .orElse(Map.of());
    }

    private static boolean typesCompatible(String expected, String actual) {
        if (expected.isBlank() || actual.isBlank()) return true;
        Map<String, Set<String>> groups = Map.of(
                "string", Set.of("char", "varchar", "text", "nvarchar", "nchar"),
                "datetime", Set.of("date", "datetime", "datetime2", "timestamp", "smalldatetime"),
                "integer", Set.of("tinyint", "smallint", "int", "integer", "bigint"),
                "numeric", Set.of("decimal", "numeric", "float", "double", "real", "money"),
                "boolean", Set.of("bool", "boolean", "tinyint", "bit"),
                "code", Set.of("char", "varchar", "nvarchar", "text", "tinyint", "smallint",
                        "int", "integer", "bigint", "decimal", "numeric"));
        return groups.getOrDefault(expected, Set.of(expected)).contains(actual);
    }

    private static List<LocalDate> periodDates(String value) {
        Matcher matcher = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(value);
        List<LocalDate> result = new ArrayList<>();
        while (matcher.find() && result.size() < 2) {
            try {
                result.add(LocalDate.parse(matcher.group()));
            } catch (DateTimeParseException ignored) {
                // 非法日期按缺少可比较元数据处理。
            }
        }
        return List.copyOf(result);
    }

    private static Object firstPresent(Object... values) {
        for (Object value : values) {
            if (value != null && !text(value).isBlank()) return value;
        }
        return "";
    }

    private static Object valueIgnoreCaseObject(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static String valueIgnoreCase(Map<String, Object> row, String key) {
        return text(valueIgnoreCaseObject(row, key));
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) result.add(objectMap(item));
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null && !text(value).isBlank()) target.put(key, value);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static Number number(String value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number
                ? number.doubleValue()
                : Double.parseDouble(text(value));
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null || text(value).isBlank() ? 0 : new BigDecimal(text(value)).longValue();
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    public record Input(
            String ruleId,
            String issueDescription,
            String statStartTime,
            String statEndTime,
            String fileKey) {
        public Input {
            ruleId = ruleId == null ? "" : ruleId.strip();
            issueDescription = issueDescription == null ? "" : issueDescription.strip();
            statStartTime = statStartTime == null ? "" : statStartTime.strip();
            statEndTime = statEndTime == null ? "" : statEndTime.strip();
            fileKey = fileKey == null || fileKey.isBlank() ? null : fileKey.strip();
            if (ruleId.isBlank() || issueDescription.isBlank()
                    || statStartTime.isBlank() || statEndTime.isBlank()
                    || issueDescription.length() > 2000) {
                throw new IllegalArgumentException("差异诊断参数不完整");
            }
            if (fileKey != null && (fileKey.length() > 255
                    || fileKey.contains("/") || fileKey.contains("\\"))) {
                throw new IllegalArgumentException("上传文件编号不符合安全约束");
            }
        }
    }

    private static final class DiagnosticExecution {
        private final Input input;
        private final ToolExecutionContext context;
        private final Map<String, Object> rule;
        private final Map<String, Object> mapping;
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final List<Map<String, Object>> layers = new ArrayList<>();
        private ToolResult initialUploadInspection;
        private ToolResult uploadComparison;
        private ToolResult baseline;
        private Map<String, Object> baselineLayer = Map.of();
        private String baselineSqlId;
        private String baselineRunId;

        private DiagnosticExecution(
                Input input,
                ToolExecutionContext context,
                Map<String, Object> rule,
                Map<String, Object> mapping,
                LocalDateTime start,
                LocalDateTime end) {
            this.input = input;
            this.context = context;
            this.rule = rule;
            this.mapping = mapping;
            this.start = start;
            this.end = end;
        }
    }

    private record PhysicalField(String table, String column) {
    }

    private record QueryScope(String fromClause, String scopeWhere) {
    }
}

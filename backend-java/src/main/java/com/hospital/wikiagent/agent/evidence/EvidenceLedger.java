package com.hospital.wikiagent.agent.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把工具结果转换为最小化 EvidenceEnvelope；SQL 正文和患者级数据只保存对象引用，
 * 不进入模型上下文、Trace 或普通会话。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class EvidenceLedger implements EvidenceRecorder {
    private static final Set<String> SAFE_DATA_KEYS = Set.of(
            "ruleId", "ruleName", "definition", "formula", "effectiveLevel",
            "numeratorRule", "denominatorRule", "filterRule", "excludeRule",
            "calculationDefinition",
            "nationalVersion", "hospitalVersion", "version", "mappingStatus",
            "sqlStatus", "sqlId", "runId", "resultId", "sourceRole", "dbSourceId",
            "profileId", "executionStatus", "executionBlockers", "referenceOnly",
            "validationStatus",
            "canonicalRunId", "extractionId", "workflowVersion",
            "diagnosisReportId", "extractionStatus", "dataFreshness",
            "targetValue", "targetSource", "targetConflict",
            "targetComparisonStatus", "resultComparisonStatus",
            "sqlBundle",
            "statStart", "statEnd", "statStartTime", "statEndTime",
            "numeratorCount", "denominatorCount", "resultValue", "sampleCount",
            "businessResult", "realResult", "dualDifferenceDiagnosis",
            "caliberProfileId", "caliberLabel", "caliberVersion",
            "currentProfileId", "currentProfileName", "caliberOptions",
            "caliberSourceLevel", "caliberDefinition", "caliberNumeratorRule",
            "caliberDenominatorRule", "periodAnchorLabel", "elapsedAnchorLabel",
            "fieldRoleOverrides", "differenceDimensions", "caliberSqlId",
            "currentRuleId", "currentRuleName", "currentRuleVersion",
            "fileKey", "sheetCount", "rowCount", "columns", "summary",
            "containsDetailRecords", "fileEvidenceType",
            "fileName", "looksLikeIndicatorData", "uploadedRuleId",
            "uploadedRuleName", "uploadedStatPeriod", "uploadedNumerator",
            "uploadedDenominator", "uploadedRate", "systemRuleId",
            "systemStatPeriod", "systemNumerator", "systemDenominator",
            "systemRate", "comparisonLevel", "comparisonStatus",
            "comparisonDirection", "comparisonMetrics", "matchedCount",
            "differentCount", "causeAnalysisNote",
            "diagnoseStatus", "userSummary", "layers", "statPeriod",
            "rowLevelComparisonAvailable", "causeAnalysisAvailable",
            "matchingFields", "commonFields",
            "systemOnlyFields", "uploadedOnlyFields", "systemCount",
            "uploadedCount", "bothCount", "systemOnlyCount",
            "uploadedOnlyCount", "fieldDifferenceCount",
            "systemNumeratorCount", "uploadedNumeratorCount",
            "systemOnlyNumeratorCount", "uploadedOnlyNumeratorCount",
            "classificationDifferenceCount", "confirmedFindings",
            "reportId", "reportSchemaVersion", "overallStatus", "stages",
            "conclusionCode", "stoppedLayer", "causeConfirmed",
            "affectedRecordCount", "baselineResult", "externalEvidence",
            "caliberCandidates", "caliberCauseLikely",
            "baselineRunId", "baselineSqlId", "evidenceLimit",
            "passedStages", "warningStages", "failedStages", "skippedStages",
            "targetLevel", "currentEffectiveLevel", "requested", "currentEffective",
            "fieldChanges", "impact", "message");

    private static final Map<String, List<String>> FACT_TYPES = Map.ofEntries(
            Map.entry("search_indicator_rules", List.of("rule_identity")),
            Map.entry("get_effective_rule", List.of("effective_rule", "definition", "formula")),
            Map.entry("inspect_indicator_implementation", List.of("implementation_status", "field_mapping")),
            Map.entry("prepare_indicator_sql", List.of("sql_validation")),
            Map.entry("trial_run_indicator_sql", List.of("trial_run")),
            Map.entry("list_indicator_calibers", List.of("caliber_options")),
            Map.entry("resolve_indicator_caliber", List.of("caliber_profile")),
            Map.entry("prepare_indicator_caliber_sql", List.of("caliber_sql_validation")),
            Map.entry("trial_run_indicator_caliber_sql", List.of("caliber_trial_result")),
            Map.entry("diagnose_indicator_issue", List.of("diagnosis")),
            Map.entry("diagnose_indicator_difference", List.of("difference_diagnosis_report")),
            Map.entry("preview_rule_change", List.of("rule_change_preview")),
            Map.entry("analyze_uploaded_indicators", List.of("file_analysis")));

    private final EvidenceStore store;
    private final ObjectMapper objectMapper;
    private final int ttlDays;

    public EvidenceLedger(
            EvidenceStore store,
            ObjectMapper objectMapper,
            AgentModelProperties properties) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.ttlDays = Math.max(1, properties.getEvidenceTtlDays());
    }

    @Override
    public ToolResult recordToolResult(
            String toolName,
            Map<String, Object> arguments,
            ToolResult result,
            AgentRuntimeContext context,
            AgentRunState state) {
        if (!result.ok()) {
            return result;
        }
        Instant now = Instant.now();
        String subtaskId = state.subtaskId() == null || state.subtaskId().isBlank()
                ? context.requestId() : state.subtaskId();
        Map<String, Object> safePayload = safePayload(result.data());
        String sourceObjectId = sourceObjectId(result.data());
        boolean sensitiveReference = sourceObjectId != null
                && (sourceObjectId.startsWith("SQL_") || sourceObjectId.startsWith("RUN_")
                || sourceObjectId.startsWith("SNAP_") || sourceObjectId.startsWith("IVR_"));
        String inputFingerprint = fingerprint(Map.of("tool", toolName, "arguments", arguments));
        String resultFingerprint = fingerprint(result.withEvidenceIds(List.of()));
        List<String> evidenceIds = new ArrayList<>();
        for (String factType : factTypes(toolName, result.data())) {
            String evidenceId = "EVD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            EvidenceEnvelope envelope = new EvidenceEnvelope(
                    EvidenceEnvelope.VERSION,
                    evidenceId,
                    context.traceId(),
                    subtaskId,
                    factType,
                    context.hospitalId(),
                    text(result.data().get("ruleId"), state.currentRuleId()),
                    text(result.data().get("hospitalVersion"), result.data().get("version")),
                    text(result.data().get("statStart"), result.data().get("statStartTime")),
                    text(result.data().get("statEnd"), result.data().get("statEndTime")),
                    toolName,
                    sourceObjectId,
                    inputFingerprint,
                    resultFingerprint,
                    sensitiveReference ? "sensitive_reference" : "internal",
                    now,
                    now.plus(ttlDays, ChronoUnit.DAYS),
                    sensitiveReference ? sourceObjectId : null,
                    safePayload);
            store.saveEvidence(envelope);
            evidenceIds.add(evidenceId);
        }
        state.evidenceIds().addAll(evidenceIds.stream()
                .filter(id -> !state.evidenceIds().contains(id)).toList());
        return result.withEvidenceIds(evidenceIds);
    }

    /**
     * 双库试运行需要分别记录两侧结果和比较结论。仍保留 {@code trial_run}，以兼容
     * 现有状态控制器、最终回答和明细入口；新增事实只承担来源隔离与一致性审计。
     */
    private static List<String> factTypes(String toolName, Map<String, Object> data) {
        if ("trial_run_indicator_sql".equals(toolName)
                && data.containsKey("comparisonStatus")) {
            return List.of(
                    "trial_run",
                    "source_extraction_completed",
                    "business_overview_result",
                    "real_overview_result",
                    "dual_result_comparison");
        }
        return FACT_TYPES.getOrDefault(toolName, List.of("tool_result"));
    }

    String fingerprint(Object value) {
        try {
            Object canonical = canonical(value);
            byte[] payload = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 Evidence 指纹", exception);
        }
    }

    private Map<String, Object> safePayload(Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : SAFE_DATA_KEYS) {
            if (data.containsKey(key)) {
                result.put(key, data.get(key));
            }
        }
        return result;
    }

    private static String sourceObjectId(Map<String, Object> data) {
        for (String key : List.of("reportId", "runId", "sqlId", "resultId", "fileKey")) {
            String value = text(data.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private static String text(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}

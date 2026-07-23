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
            "rule_id", "rule_name", "definition", "formula", "effective_level",
            "national_version", "hospital_version", "version", "mapping_status",
            "sql_status", "sql_id", "run_id", "result_id", "db_source_id",
            "stat_start", "stat_end", "stat_start_time", "stat_end_time",
            "numerator_count", "denominator_count", "result_value", "sample_count",
            "caliber_profile_id", "caliber_label", "caliber_version",
            "caliber_source_level", "caliber_definition", "caliber_numerator_rule",
            "caliber_denominator_rule", "period_anchor_label", "elapsed_anchor_label",
            "field_role_overrides", "difference_dimensions", "caliber_sql_id",
            "current_rule_id", "current_rule_name", "current_rule_version",
            "file_key", "sheet_count", "row_count", "columns", "summary",
            "contains_detail_records", "file_evidence_type",
            "file_name", "looks_like_indicator_data", "uploaded_rule_id",
            "uploaded_rule_name", "uploaded_stat_period", "uploaded_numerator",
            "uploaded_denominator", "uploaded_rate", "system_rule_id",
            "system_stat_period", "system_numerator", "system_denominator",
            "system_rate", "comparison_level", "comparison_status",
            "comparison_direction", "comparison_metrics", "matched_count",
            "different_count", "cause_analysis_note",
            "diagnose_status", "user_summary", "layers", "stat_period",
            "row_level_comparison_available", "cause_analysis_available",
            "matching_fields", "common_fields",
            "system_only_fields", "uploaded_only_fields", "system_count",
            "uploaded_count", "both_count", "system_only_count",
            "uploaded_only_count", "field_difference_count",
            "system_numerator_count", "uploaded_numerator_count",
            "system_only_numerator_count", "uploaded_only_numerator_count",
            "classification_difference_count", "confirmed_findings",
            "report_id", "report_schema_version", "overall_status", "stages",
            "conclusion_code", "stopped_layer", "cause_confirmed",
            "affected_record_count", "baseline_result", "external_evidence",
            "caliber_candidates", "caliber_cause_likely",
            "baseline_run_id", "baseline_sql_id", "evidence_limit",
            "passed_stages", "warning_stages", "failed_stages", "skipped_stages",
            "target_level", "current_effective_level", "requested", "current_effective",
            "field_changes", "impact", "message");

    private static final Map<String, List<String>> FACT_TYPES = Map.ofEntries(
            Map.entry("search_indicator_rules", List.of("rule_identity")),
            Map.entry("get_effective_rule", List.of("effective_rule", "definition", "formula")),
            Map.entry("inspect_indicator_implementation", List.of("implementation_status", "field_mapping")),
            Map.entry("prepare_indicator_sql", List.of("sql_validation")),
            Map.entry("trial_run_indicator_sql", List.of("trial_run")),
            Map.entry("resolve_indicator_caliber", List.of("caliber_profile")),
            Map.entry("prepare_indicator_caliber_sql", List.of("caliber_sql_validation")),
            Map.entry("trial_run_indicator_caliber_sql", List.of("caliber_trial_result")),
            Map.entry("diagnose_indicator_issue", List.of("diagnosis")),
            Map.entry("diagnose_indicator_difference", List.of("difference_diagnosis_report")),
            Map.entry("preview_rule_change", List.of("rule_change_preview")),
            Map.entry("analyze_uploaded_indicators", List.of("file_analysis")),
            Map.entry("validate_indicator_implementation", List.of("implementation_validation_report")));

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
        for (String factType : FACT_TYPES.getOrDefault(toolName, List.of("tool_result"))) {
            String evidenceId = "EVD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            EvidenceEnvelope envelope = new EvidenceEnvelope(
                    EvidenceEnvelope.VERSION,
                    evidenceId,
                    context.traceId(),
                    subtaskId,
                    factType,
                    context.hospitalId(),
                    text(result.data().get("rule_id"), state.currentRuleId()),
                    text(result.data().get("hospital_version"), result.data().get("version")),
                    text(result.data().get("stat_start"), result.data().get("stat_start_time")),
                    text(result.data().get("stat_end"), result.data().get("stat_end_time")),
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
        for (String key : List.of("report_id", "run_id", "sql_id", "result_id", "file_key")) {
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

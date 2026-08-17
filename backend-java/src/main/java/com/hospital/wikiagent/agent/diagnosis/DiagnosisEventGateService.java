package com.hospital.wikiagent.agent.diagnosis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.MrasParameterMapper;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.dto.MultiCaliberEnableDto;
import com.hospital.wikiagent.service.SyncDataException;
import com.hospital.wikiagent.service.SyncDataService;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;

/**
 * 第二步“事件与抽取校验”。事件候选来自写入同一中间表的全部已实现口径，
 * 现场启用状态直接复用正式抽取服务；事件与脚本检查通过后，每次都通过正式概览
 * 计算链路重新计算当前口径和统计窗口，并把本次结果冻结为第三步的数据可用性证据。
 */
@Component
public class DiagnosisEventGateService {
    private static final String MANUAL_EVENT_CHECK_SUGGESTION = "请人工检查是否有重复事件启用。";
    private final EntityPageParser entities;
    private final SyncDataService syncDataService;
    private final SqlServerProperties sqlServerProperties;
    private final MrasSqlExecutionService mrasExecution;
    private final ReadOnlySqlValidator sqlValidator;
    private final MrasParameterMapper parameters;
    private final MrasTemplateRenderer renderer;

    public DiagnosisEventGateService(
            EntityPageParser entities,
            SyncDataService syncDataService,
            SqlServerProperties sqlServerProperties,
            MrasSqlExecutionService mrasExecution,
            ReadOnlySqlValidator sqlValidator,
            MrasParameterMapper parameters,
            MrasTemplateRenderer renderer) {
        this.entities = entities;
        this.syncDataService = syncDataService;
        this.sqlServerProperties = sqlServerProperties;
        this.mrasExecution = mrasExecution;
        this.sqlValidator = sqlValidator;
        this.parameters = parameters;
        this.renderer = renderer;
    }

    public Map<String, Object> run(
            String hospitalId, String ruleId, String profileId,
            LocalDateTime start, LocalDateTime end) {
        EntityPageData entity = entities.getEntity(profileId, hospitalId);
        if (entity == null) entity = entities.getEntity(ruleId, hospitalId);
        if (entity == null) return blocked("EVENT_ENTITY_MISSING", "当前口径实体不存在。", Map.of());

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("targetTable", text(entity.targetTable()));
        facts.put("sourceSqlValidation", validateScript(entity.sourceTableSql(), start, end));

        // 本步骤每次都必须产生一份当前窗口的新计算证据；即使后续发现多事件或脚本问题，
        // 也保留本次真实执行结果，避免再次依赖历史批次猜测抽取是否正常。
        long calculationStarted = System.currentTimeMillis();
        ToolResult calculation = mrasExecution.executeOverview(
                ruleId, profileId, hospitalId, start, end, null, null);
        Map<String, Object> execution = executionFacts(
                calculation, start, end, System.currentTimeMillis() - calculationStarted);
        facts.put("executionEvidence", execution);

        Set<String> candidates = collectCandidateEvents(hospitalId, entity.targetTable());
        facts.put("candidateEvents", List.copyOf(candidates));
        List<String> enabled = List.of();
        if (!candidates.isEmpty()) {
            Long hospitalSoid = sqlServerProperties.getHospitalSoid();
            if (hospitalSoid == null) {
                return blocked("HOSPITAL_SOID_MISSING", "服务端未配置医院 SOID，无法核对现场事件。", facts);
            }
            MultiCaliberEnableDto request = new MultiCaliberEnableDto();
            request.setHospitalSOID(hospitalSoid);
            request.setEventNoList(List.copyOf(candidates));
            try {
                enabled = syncDataService.multiCaliberEnableJudge(request);
            } catch (SyncDataException exception) {
                facts.put("hospitalSOID", hospitalSoid);
                return blocked(exception.code(), safeMessage(exception), facts);
            }
            facts.put("hospitalSOID", hospitalSoid);
        }
        facts.put("enabledEvents", enabled);
        if (enabled.size() > 1) {
            facts.put("enabledEventCount", enabled.size());
            return blocked("MULTIPLE_CALIBER_EVENTS_ENABLED",
                    "现场同时启用了多个口径事件，可能造成重复数据：" + String.join("、", enabled), facts);
        }

        Map<?, ?> script = (Map<?, ?>) facts.get("sourceSqlValidation");
        if (!Boolean.TRUE.equals(script.get("ok"))) {
            return blocked("EVENT_SCRIPT_INVALID",
                    "当前抽取脚本校验未通过：" + text(script.get("message")), facts);
        }

        if (!calculation.ok()) {
            return blocked(text(calculation.code()).isBlank()
                            ? "DIAGNOSIS_CALCULATION_FAILED" : calculation.code(),
                    text(calculation.summary()).isBlank()
                            ? "本次重新计算失败。" : calculation.summary(), facts);
        }
        return passed("知识库候选事件" + candidates.size() + "个，现场未发现多口径同时启用；"
                + "本次校验已重新计算当前口径，状态为" + execution.get("status") + "。", facts);
    }

    private Set<String> collectCandidateEvents(String hospitalId, String targetTable) {
        Set<String> values = new LinkedHashSet<>();
        if (text(targetTable).isBlank()) return values;
        for (EntityPageData candidate : entities.getAllEntities(hospitalId).values()) {
            String event = text(candidate.eventNo());
            if (!candidate.hasOverviewSql() || event.isBlank() || "—".equals(event)) continue;
            if (targetTable.equalsIgnoreCase(text(candidate.targetTable()))) values.add(event);
        }
        return values;
    }

    private Map<String, Object> validateScript(
            String sql, LocalDateTime start, LocalDateTime end) {
        if (text(sql).isBlank()) return Map.of("ok", true, "message", "无独立抽取脚本");
        Map<String, Object> values = new LinkedHashMap<>(parameters.mapTimeOnly(start, end));
        values.put("syncType", "outHosp");
        String rendered = MrasSqlExecutionService.stripLeadingTrailingQuotes(
                renderer.renderTemplate(sql, values));
        var result = sqlValidator.validateReadOnly(rendered);
        return Map.of("ok", result.ok(), "message", result.message());
    }

    private static Map<String, Object> executionFacts(
            ToolResult value, LocalDateTime start, LocalDateTime end, long durationMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> data = value.data();
        boolean noSample = Boolean.TRUE.equals(data.get("noSample"));
        result.put("calculationId", "DIAG_CALC_" + java.util.UUID.randomUUID()
                .toString().replace("-", "").substring(0, 20));
        result.put("evidenceSource", "FRESH_DIAGNOSIS_CALCULATION");
        result.put("executedAt", Instant.now().toString());
        result.put("durationMs", Math.max(0, durationMs));
        result.put("status", value.ok() ? (noSample ? "NO_SAMPLE" : "SUCCESS") : "FAILED");
        copyIfPresent(data, result, "resultValue", "numeratorCount", "denominatorCount",
                "sampleCount", "overviewSqlHash", "detailKind", "targetValue",
                "targetDirection", "unit", "qualifiedLabel");
        result.put("attainmentLabel", attainmentLabel(data));
        result.put("errorCode", value.ok() ? "" : text(value.code()));
        result.put("errorMessage", value.ok() ? "" : text(value.summary()));
        result.put("statStart", start.toString());
        result.put("statEnd", end.toString());
        return Map.copyOf(result);
    }

    static String attainmentLabel(Map<String, Object> data) {
        String qualified = text(data.get("qualifiedLabel"));
        if (qualified.contains("未达标")) return "未达标";
        if (qualified.contains("达标")) return "达标";
        BigDecimal actual = decimal(data.get("resultValue"));
        BigDecimal target = decimal(data.get("targetValue"));
        String direction = text(data.get("targetDirection")).replace(" ", "");
        if (actual == null || target == null || direction.isBlank()) return "待判定";
        int comparison = actual.compareTo(target);
        boolean attained = switch (direction.toLowerCase(java.util.Locale.ROOT)) {
            case ">", "gt" -> comparison > 0;
            case ">=", "≥", "up", "gte" -> comparison >= 0;
            case "<", "lt" -> comparison < 0;
            case "<=", "≤", "down", "lte" -> comparison <= 0;
            default -> false;
        };
        boolean knownDirection = Set.of(
                ">", "gt", ">=", "≥", "up", "gte",
                "<", "lt", "<=", "≤", "down", "lte")
                .contains(direction.toLowerCase(java.util.Locale.ROOT));
        return knownDirection ? (attained ? "达标" : "未达标") : "待判定";
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(String.valueOf(value).replace("%", "").strip());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void copyIfPresent(
            Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.get(key) != null) target.put(key, source.get(key));
        }
    }

    private static Map<String, Object> passed(String message, Map<String, Object> facts) {
        return result("PASSED", "", message, facts);
    }

    private static Map<String, Object> blocked(String code, String message, Map<String, Object> facts) {
        return result("BLOCKED", code, message, facts);
    }

    private static Map<String, Object> result(
            String status, String code, String message, Map<String, Object> facts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate", 2);
        result.put("name", "事件与抽取校验");
        result.put("status", status);
        result.put("errorCode", code);
        result.put("message", message);
        if ("BLOCKED".equals(status)) {
            result.put("repairSuggestion", MANUAL_EVENT_CHECK_SUGGESTION);
        }
        result.put("facts", Map.copyOf(facts));
        return Map.copyOf(result);
    }

    private static String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName()
                : value.replaceAll("(?i)(password|pwd|token)=[^;\\s]+", "$1=***");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}

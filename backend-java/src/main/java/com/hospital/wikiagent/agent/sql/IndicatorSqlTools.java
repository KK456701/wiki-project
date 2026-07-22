package com.hospital.wikiagent.agent.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.rules.RuleReadRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 根据 Wiki 中的规则规格确定性准备 SQL 对象，并仅通过 DBHub 执行受控只读试运行。
 * 浏览器和模型均不能向这里提交任意 SQL 正文。
 *
 * <p>能力只能经 ToolGateway 的权限、参数和重复调用检查后执行，不能由模型绕过网关直接调用。返回值必须形成可验证 Evidence，再交给最终答案使用。</p>
 */
@Component
public class IndicatorSqlTools {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration SQL_TTL = Duration.ofMinutes(30);

    private final RuleReadRepository rules;
    private final SqlObjectRepository objects;
    private final SqlTemplateRenderer renderer;
    private final ReadOnlySqlValidator validator;
    private final SqlParameterBinder binder;
    private final IndicatorBusinessQueryClient businessQuery;
    private final ObjectMapper objectMapper;

    public IndicatorSqlTools(
            RuleReadRepository rules,
            SqlObjectRepository objects,
            SqlTemplateRenderer renderer,
            ReadOnlySqlValidator validator,
            SqlParameterBinder binder,
            IndicatorBusinessQueryClient businessQuery,
            ObjectMapper objectMapper) {
        this.rules = rules;
        this.objects = objects;
        this.renderer = renderer;
        this.validator = validator;
        this.binder = binder;
        this.businessQuery = businessQuery;
        this.objectMapper = objectMapper;
    }

    public ToolResult inspect(InspectInput input, ToolExecutionContext context) {
        Map<String, Object> rule = rules.effectiveRule(input.ruleId(), context.agentContext().hospitalId());
        Map<String, Object> mapping = rules.fieldMapping(input.ruleId(), context.agentContext().hospitalId());
        Inspection inspection = inspection(rule, mapping);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rule_id", input.ruleId());
        data.put("hospital_id", context.agentContext().hospitalId());
        data.put("status", mapping.get("status"));
        data.put("mapping_status", mapping.get("status"));
        data.put("dialect", mapping.get("dialect"));
        data.put("main_table", mapping.get("main_table"));
        data.put("mapped_fields", inspection.mappedFields());
        data.put("required_business_fields", inspection.requiredFields());
        data.put("missing_mappings", inspection.missingMappings());
        data.put("unconfirmed_mappings", inspection.unconfirmedMappings());
        data.put("missing_columns", inspection.missingColumns());
        data.put("type_mismatches", inspection.typeMismatches());
        data.put("missing_relations", inspection.missingRelations());
        data.put("mapping_items", safeItems(listOfMaps(mapping.get("items"))));
        data.put("relations", safeRelations(listOfMaps(mapping.get("relations"))));
        data.put("query_profile", mapping.get("query_profile"));
        data.put("sql_status", rule.getOrDefault("sql_status", "unavailable"));
        String summary = inspection.ready()
                ? "指标实施映射已确认。"
                : "指标实施仍有缺失或未确认映射。";
        return ToolResult.success("IMPLEMENTATION_INSPECTED", summary, data);
    }

    public ToolResult prepare(PrepareInput input, ToolExecutionContext context) {
        AgentRunState state = context.runState();
        if (state.currentRuleId() == null || !state.currentRuleId().equals(input.ruleId())) {
            return failure("validation_failed", "RULE_NOT_VERIFIED", "该指标尚未经过规则搜索或读取，不能准备 SQL。", false);
        }
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(input.statStartTime());
            end = LocalDateTime.parse(input.statEndTime());
        } catch (RuntimeException exception) {
            return failure("validation_failed", "STAT_PERIOD_INVALID", "统计时间格式无效。", false);
        }
        if (!start.isBefore(end)) {
            return failure("validation_failed", "STAT_PERIOD_INVALID", "统计开始时间必须早于结束时间。", false);
        }

        Map<String, Object> rule = rules.effectiveRule(input.ruleId(), context.agentContext().hospitalId());
        Map<String, Object> mapping = withExecutionDefaults(
                rules.fieldMapping(input.ruleId(), context.agentContext().hospitalId()));
        Inspection inspection = inspection(rule, mapping);
        if (!inspection.ready()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("missing_mappings", inspection.missingMappings());
            data.put("unconfirmed_mappings", inspection.unconfirmedMappings());
            data.put("missing_columns", inspection.missingColumns());
            data.put("type_mismatches", inspection.typeMismatches());
            data.put("missing_relations", inspection.missingRelations());
            return failure("validation_failed", "FIELD_PRECHECK_FAILED",
                    "字段映射或元数据预检查未通过，暂不能准备 SQL。", false, data);
        }
        String template = text(rule.get("standard_sql"));
        if (template.isBlank()) {
            return failure("validation_failed", "SQL_TEMPLATE_UNAVAILABLE", "当前生效规则没有可用 SQL 模板。", false);
        }

        String sql;
        try {
            sql = renderer.render(template, objectMap(mapping.get("fields")), text(mapping.get("main_table")));
        } catch (RuntimeException exception) {
            return failure("validation_failed", "SQL_TEMPLATE_RENDER_FAILED", "SQL 模板无法根据已确认映射完成渲染。", false);
        }
        ReadOnlySqlValidator.ValidationResult validation = validator.validate(sql, text(mapping.get("main_table")));
        if (!validation.ok()) {
            return failure("validation_failed", "SQL_VALIDATION_FAILED",
                    "生成的 SQL 未通过只读安全校验，不能进入试运行。", false);
        }

        Map<String, Object> params = objectMap(rule.get("effective_params"));
        String statStart = start.format(SQL_TIME);
        String statEnd = end.format(SQL_TIME);
        String sourceId = sourceId(context);
        Map<String, Object> snapshot = contextSnapshot(rule, mapping, params, statStart, statEnd, sourceId);
        String digest = digest(snapshot);
        String sqlId = id("SQL_");
        Instant now = Instant.now();
        PreparedSqlObject sqlObject = new PreparedSqlObject(
                sqlId, context.agentContext().hospitalId(), context.agentContext().userId(),
                context.agentContext().sessionId(), input.ruleId(), text(mapping.get("dialect")), sql,
                params, statStart, statEnd, snapshot, digest, "validated", validation.message(),
                now, now.plus(SQL_TTL), sourceId);
        try {
            objects.save(sqlObject);
        } catch (RuntimeException exception) {
            return failure("error", "SQL_OBJECT_SAVE_FAILED", "SQL 对象保存失败，请重新准备。", false);
        }

        state.currentRuleId(input.ruleId());
        if (!state.validatedSqlIds().contains(sqlId)) {
            state.validatedSqlIds().add(sqlId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sql_id", sqlId);
        data.put("rule_id", input.ruleId());
        data.put("hospital_id", context.agentContext().hospitalId());
        data.put("db_source_id", sourceId);
        data.put("context_digest", digest);
        data.put("dialect", sqlObject.dialect());
        data.put("validation_status", "validated");
        data.put("sql_preview", sql);
        Map<String, Object> displayParameters = new LinkedHashMap<>(params);
        displayParameters.put("hospital_id", context.agentContext().hospitalId());
        displayParameters.put("start_time", statStart);
        displayParameters.put("end_time", statEnd);
        data.put("parameters", displayParameters);
        data.put("stat_start", statStart);
        data.put("stat_end", statEnd);
        data.put("expires_at", sqlObject.expiresAt().toString());
        return ToolResult.success(
                "SQL_OBJECT_PREPARED", "SQL 已完成确定性生成和只读安全校验，可进行受控试运行。", data);
    }

    public ToolResult trial(TrialInput input, ToolExecutionContext context) {
        AgentRunState state = context.runState();
        if (!state.validatedSqlIds().contains(input.sqlId())) {
            return failure("unavailable", "SQL_OBJECT_NOT_ACTIVE", "该 SQL 对象不在当前已验证状态中，请重新准备。", false);
        }
        PreparedSqlObject sql;
        try {
            sql = objects.loadForExecution(input.sqlId(), context.agentContext(), Instant.now());
        } catch (SqlObjectAccessException exception) {
            if (Set.of("SQL_OBJECT_NOT_FOUND", "SQL_OBJECT_EXPIRED", "SQL_OBJECT_NOT_VALIDATED", "SQL_OBJECT_CORRUPTED")
                    .contains(exception.code())) {
                state.validatedSqlIds().remove(input.sqlId());
            }
            boolean forbidden = exception.code().contains("MISMATCH");
            return failure(forbidden ? "forbidden" : "unavailable", exception.code(), exception.getMessage(), false);
        }

        Map<String, Object> currentRule = rules.effectiveRule(sql.ruleId(), context.agentContext().hospitalId());
        Map<String, Object> currentMapping = withExecutionDefaults(
                rules.fieldMapping(sql.ruleId(), context.agentContext().hospitalId()));
        Inspection inspection = inspection(currentRule, currentMapping);
        if (!inspection.ready()) {
            return failure("validation_failed", "SQL_CONTEXT_STALE",
                    "医院字段或元数据已变化，请重新准备 SQL 后再试运行。", false);
        }
        String currentDigest = digest(contextSnapshot(
                currentRule, currentMapping, sql.params(), sql.statStart(), sql.statEnd(), sql.dbSourceId()));
        if (!currentDigest.equals(sql.contextDigest())) {
            return failure("validation_failed", "SQL_CONTEXT_STALE",
                    "指标规则或字段映射已变化，请重新准备 SQL 后再试运行。", false);
        }
        if (!validator.validate(sql.sqlText(), text(currentMapping.get("main_table"))).ok()) {
            return failure("validation_failed", "SQL_REVALIDATION_FAILED", "SQL 在试运行前未通过二次只读安全校验。", false);
        }
        if (sql.dbSourceId() != null && !sql.dbSourceId().isBlank()
                && !sql.dbSourceId().equals(businessQuery.sourceId())) {
            return failure("error", "TRIAL_SOURCE_MISMATCH", "试运行数据源与 SQL 对象不一致，结果已拒绝。", false);
        }

        Map<String, Object> bound = new LinkedHashMap<>(sql.params());
        bound.put("hospital_id", context.agentContext().hospitalId());
        bound.put("start_time", sql.statStart());
        bound.put("end_time", sql.statEnd());
        String executable;
        try {
            executable = binder.bind(sql.sqlText(), bound);
        } catch (RuntimeException exception) {
            return failure("validation_failed", "SQL_PARAMETER_MISSING", "SQL 运行参数不完整，请重新准备。", false);
        }

        String runId = id("RUN_");
        long started = System.nanoTime();
        try {
            List<Map<String, Object>> rows;
            try {
                rows = businessQuery.execute(executable);
            } catch (DbHubMcpException exception) {
                if (!transientConnectionFailure(exception.getMessage())) {
                    throw exception;
                }
                rows = businessQuery.execute(executable);
            }
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
            Number resultValue = number(value(first, "index_value"));
            Long numerator = longValue(value(first, "numerator_count"));
            Long denominator = longValue(value(first, "denominator_count"));
            if (denominator == null) {
                denominator = longValue(value(first, "sample_count"));
            }
            String status = resultValue == null ? "empty" : "success";
            Map<String, Object> runContext = new LinkedHashMap<>(sql.contextSnapshot());
            objects.saveRun(runId, sql, status, resultValue, numerator, denominator, "", durationMs,
                    context.agentContext().userId(), runContext);
            state.lastRunId(runId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sql_id", sql.sqlId());
            data.put("run_id", runId);
            data.put("status", status);
            data.put("result_value", resultValue);
            data.put("numerator_count", numerator);
            data.put("denominator_count", denominator);
            data.put("no_sample", denominator != null && denominator == 0);
            data.put("duration_ms", durationMs);
            data.put("source", businessQuery.sourceId());
            data.put("hospital_id", context.agentContext().hospitalId());
            data.put("db_source_id", sql.dbSourceId());
            data.put("rule_id", sql.ruleId());
            data.put("context_digest", sql.contextDigest());
            data.put("stat_start", sql.statStart());
            data.put("stat_end", sql.statEnd());
            return ToolResult.success("TRIAL_RUN_COMPLETED",
                    "success".equals(status) ? "只读试运行完成，已获得聚合结果。"
                            : "只读试运行完成，当前统计区间没有可用样本。",
                    data);
        } catch (RuntimeException exception) {
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            try {
                objects.saveRun(runId, sql, "failed", null, null, null, "DBHub query failed", durationMs,
                        context.agentContext().userId(), sql.contextSnapshot());
            } catch (RuntimeException ignored) {
                // 原始 DBHub 错误优先，日志失败不能泄漏内部连接信息。
            }
            return failure("error", "TRIAL_RUN_FAILED", "只读试运行失败，未获得可用聚合结果。", true);
        }
    }

    private Inspection inspection(Map<String, Object> rule, Map<String, Object> mapping) {
        Set<String> required = new LinkedHashSet<>();
        Map<String, Object> businessFields = objectMap(
                objectMap(rule.get("field_contract")).get("business_fields"));
        required.addAll(businessFields.keySet());
        Set<String> mapped = new LinkedHashSet<>(objectMap(mapping.get("fields")).keySet());
        List<String> missing = required.stream().filter(value -> !mapped.contains(value)).sorted().toList();
        List<String> unconfirmed = listOfMaps(mapping.get("items")).stream()
                .filter(item -> !"confirmed".equals(text(item.get("status"))))
                .map(item -> text(item.get("business_field"))).filter(value -> !value.isBlank()).distinct().sorted().toList();
        List<String> missingColumns = new ArrayList<>();
        List<String> typeMismatches = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(mapping.get("metadata_items"))) {
            String businessField = text(item.get("business_field"));
            if (!required.contains(businessField)) continue;
            String mappedColumn = text(item.get("table_name")) + "." + text(item.get("column_name"));
            String actual = text(item.get("metadata_data_type")).toLowerCase(Locale.ROOT);
            if (actual.isBlank()) {
                missingColumns.add(mappedColumn);
                continue;
            }
            String expected = text(objectMap(businessFields.get(businessField)).get("type"))
                    .toLowerCase(Locale.ROOT);
            if (!typesCompatible(expected, actual)) {
                typeMismatches.add(businessField + "：期望 " + expected + "，实际 " + actual + "（" + mappedColumn + "）");
            }
        }
        Set<String> physicalTables = new LinkedHashSet<>();
        for (String value : objectMap(mapping.get("fields")).values().stream().map(String::valueOf).toList()) {
            String[] parts = value.split("\\.");
            if (parts.length >= 2) physicalTables.add(parts[parts.length - 2]);
        }
        String mainTable = text(mapping.get("main_table"));
        List<String> missingRelations = new ArrayList<>();
        List<Map<String, Object>> relations = listOfMaps(mapping.get("relations"));
        for (String other : physicalTables) {
            if (other.equals(mainTable)) continue;
            boolean found = relations.stream().anyMatch(relation ->
                    (mainTable.equals(text(relation.get("left_table"))) && other.equals(text(relation.get("right_table"))))
                            || (mainTable.equals(text(relation.get("right_table"))) && other.equals(text(relation.get("left_table")))));
            if (!found) missingRelations.add(mainTable + " -> " + other);
        }
        boolean ready = "confirmed".equals(mapping.get("status"))
                && missing.isEmpty() && unconfirmed.isEmpty() && missingColumns.isEmpty()
                && typeMismatches.isEmpty() && missingRelations.isEmpty();
        return new Inspection(ready, mapped.stream().sorted().toList(), required.stream().sorted().toList(),
                missing, unconfirmed, missingColumns.stream().sorted().toList(),
                typeMismatches.stream().sorted().toList(), missingRelations.stream().sorted().toList());
    }

    private static boolean typesCompatible(String expected, String actual) {
        if (expected.isBlank() || actual.isBlank()) return true;
        Map<String, Set<String>> groups = Map.of(
                "string", Set.of("char", "varchar", "text", "tinytext", "mediumtext", "longtext", "nvarchar", "nchar"),
                "datetime", Set.of("date", "datetime", "datetime2", "timestamp", "smalldatetime"),
                "integer", Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint"),
                "numeric", Set.of("decimal", "numeric", "float", "double", "real", "money", "smallmoney"),
                "boolean", Set.of("bool", "boolean", "tinyint", "bit"),
                "code", Set.of("char", "varchar", "nvarchar", "text", "tinyint", "smallint", "mediumint", "int",
                        "integer", "bigint", "decimal", "numeric"));
        return groups.getOrDefault(expected, Set.of(expected)).contains(actual);
    }

    private Map<String, Object> contextSnapshot(
            Map<String, Object> rule,
            Map<String, Object> mapping,
            Map<String, Object> params,
            String start,
            String end,
            String sourceId) {
        Map<String, Object> ruleSnapshot = new LinkedHashMap<>(rule);
        String sql = text(ruleSnapshot.remove("standard_sql"));
        if (!sql.isBlank()) {
            ruleSnapshot.put("standard_sql_sha256", sha256(sql));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("effective_rule", ruleSnapshot);
        result.put("field_mapping", mapping);
        result.put("execution_context", Map.of());
        result.put("params", params);
        result.put("stat_start", start);
        result.put("stat_end", end);
        result.put("db_source_id", sourceId);
        return result;
    }

    private Map<String, Object> withExecutionDefaults(Map<String, Object> raw) {
        Map<String, Object> mapping = deepMap(raw);
        Map<String, Object> fields = objectMap(mapping.get("fields"));
        String admit = text(fields.get("admit_time"));
        if (!admit.isBlank()) {
            fields.putIfAbsent("baseline_admit_time", admit);
            fields.putIfAbsent("period_time", admit);
        }
        mapping.put("fields", fields);
        return mapping;
    }

    private String digest(Object value) {
        try {
            return sha256(objectMapper.writeValueAsString(canonical(value)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 SQL 上下文指纹", exception);
        }
    }

    private Object canonical(Object value) {
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

    private Map<String, Object> deepMap(Map<String, Object> value) {
        return objectMapper.convertValue(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(objectMap(item));
        }
        return result;
    }

    private static List<Map<String, Object>> safeItems(List<Map<String, Object>> items) {
        return allow(items, Set.of("business_field", "table_name", "column_name", "data_type", "status"));
    }

    private static List<Map<String, Object>> safeRelations(List<Map<String, Object>> items) {
        return allow(items, Set.of("left_table", "left_column", "right_table", "right_column",
                "join_type", "relation_source", "status"));
    }

    private static List<Map<String, Object>> allow(List<Map<String, Object>> items, Set<String> keys) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> safe = new LinkedHashMap<>();
            keys.forEach(key -> {
                if (item.containsKey(key)) safe.put(key, item.get(key));
            });
            result.add(safe);
        }
        return result;
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        if (value == null || value.toString().isBlank()) return null;
        return Double.parseDouble(value.toString());
    }

    private static Long longValue(Object value) {
        Number number = number(value);
        return number == null ? null : number.longValue();
    }

    private static boolean transientConnectionFailure(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return List.of("socket hang up", "connection lost", "connection reset", "connection aborted", "连接中断", "连接已断开")
                .stream().anyMatch(value::contains);
    }

    private String sourceId(ToolExecutionContext context) {
        String requested = context.agentContext().dbSourceId();
        return requested == null || requested.isBlank() ? businessQuery.sourceId() : requested;
    }

    private static ToolResult failure(String status, String code, String summary, boolean retryable) {
        return failure(status, code, summary, retryable, Map.of());
    }

    private static ToolResult failure(
            String status, String code, String summary, boolean retryable, Map<String, Object> data) {
        return new ToolResult(false, status, code, summary, data, retryable, false, List.of());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 SHA-256", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public record InspectInput(String ruleId) {
        public InspectInput {
            ruleId = ruleId == null ? "" : ruleId.strip();
            if (ruleId.isEmpty()) throw new IllegalArgumentException("规则编号不能为空");
        }
    }

    public record PrepareInput(String ruleId, String statStartTime, String statEndTime) {
        public PrepareInput {
            ruleId = ruleId == null ? "" : ruleId.strip();
            statStartTime = statStartTime == null ? "" : statStartTime.strip();
            statEndTime = statEndTime == null ? "" : statEndTime.strip();
            if (ruleId.isEmpty() || statStartTime.isEmpty() || statEndTime.isEmpty()) {
                throw new IllegalArgumentException("SQL 准备参数不完整");
            }
        }
    }

    public record TrialInput(String sqlId) {
        public TrialInput {
            sqlId = sqlId == null ? "" : sqlId.strip();
            if (!sqlId.matches("SQL_[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("SQL 对象编号无效");
        }
    }

    private record Inspection(
            boolean ready,
            List<String> mappedFields,
            List<String> requiredFields,
            List<String> missingMappings,
            List<String> unconfirmedMappings,
            List<String> missingColumns,
            List<String> typeMismatches,
            List<String> missingRelations) {}
}

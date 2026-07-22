package com.hospital.wikiagent.rules;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RuleReadRepository {
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*分钟");
    private static final Set<String> PATCH_ROOTS = Set.of(
            "scope", "derived_fields", "denominator", "numerator", "result", "detail_fields");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RuleReadRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> searchForHospital(String query, String hospitalId, int limit) {
        String normalized = query == null ? "" : query.strip();
        String pattern = "%" + normalized + "%";
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> local = jdbc.query(
                "SELECT index_code,index_name,index_type,index_desc FROM med_index_hospital_defined "
                        + "WHERE hospital_id=? AND status=1 AND approval_status='approved' "
                        + "AND (effective_from IS NULL OR effective_from<=?) "
                        + "AND (effective_to IS NULL OR effective_to>=?) "
                        + "AND (index_code=? OR index_name=? OR index_name LIKE ? OR index_desc LIKE ?) "
                        + "ORDER BY CASE WHEN index_code=? OR index_name=? THEN 0 ELSE 1 END,index_code LIMIT ?",
                (rs, rowNum) -> match(rs, "mysql_hospital_defined"),
                hospitalId, now, now, normalized, normalized, pattern, pattern, normalized, normalized, limit);
        List<Map<String, Object>> standard = searchStandard(normalized, pattern, limit);
        List<Map<String, Object>> matches = new ArrayList<>(local);
        matches.addAll(standard);
        if (matches.isEmpty() && !normalized.isEmpty()) {
            String compact = compact(normalized);
            matches.addAll(searchLocalCompact(compact, hospitalId, now, limit));
            matches.addAll(searchStandardCompact(compact, limit));
        }
        if (matches.size() > limit) {
            matches = new ArrayList<>(matches.subList(0, limit));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", normalized);
        response.put("resolved_rule_id", matches.isEmpty() ? null : matches.get(0).get("rule_id"));
        response.put("matches", matches);
        return response;
    }

    public List<Map<String, String>> activeIndicatorNames(String hospitalId, int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        List<Map<String, String>> values = new ArrayList<>();
        jdbc.query(
                "SELECT index_code,index_name FROM med_index_standard WHERE status=1 ORDER BY index_code LIMIT ?",
                (RowCallbackHandler) result -> values.add(Map.of(
                        "rule_id", result.getString("index_code"),
                        "rule_name", result.getString("index_name"))),
                safeLimit);
        LocalDateTime now = LocalDateTime.now();
        jdbc.query(
                "SELECT index_code,index_name FROM med_index_hospital_defined "
                        + "WHERE hospital_id=? AND status=1 AND approval_status='approved' "
                        + "AND (effective_from IS NULL OR effective_from<=?) "
                        + "AND (effective_to IS NULL OR effective_to>=?) ORDER BY index_code LIMIT ?",
                (RowCallbackHandler) result -> values.add(Map.of(
                        "rule_id", result.getString("index_code"),
                        "rule_name", result.getString("index_name"))),
                hospitalId, now, now, safeLimit);
        Map<String, Map<String, String>> unique = new LinkedHashMap<>();
        for (Map<String, String> value : values) {
            unique.putIfAbsent(value.get("rule_id"), value);
        }
        return unique.values().stream().limit(safeLimit).toList();
    }

    public Map<String, Object> effectiveRule(String query, String hospitalId) {
        Map<String, Object> standard = findStandard(query);
        if (standard == null) {
            Map<String, Object> defined = findDefined(query, hospitalId);
            if (defined != null) {
                return definedEffectiveRule(defined, hospitalId);
            }
            throw new RuleNotFoundException("RULE_NOT_MIGRATED: " + query);
        }

        String indexCode = text(standard.get("index_code"));
        Map<String, Object> custom = findCustom(hospitalId, indexCode);
        Map<String, Object> nationalParams = jsonObject(standard.get("rule_params"));
        Map<String, Object> effectiveParams = new LinkedHashMap<>(nationalParams);
        Map<String, Object> nationalCalculation = jsonObject(standard.get("calculation_definition"));
        Map<String, Object> calculationPatch = jsonObject(custom == null ? null : custom.get("custom_calculation_patch"));
        Map<String, Object> calculationDefinition = mergeCalculationPatch(nationalCalculation, calculationPatch);
        List<String> overridden = new ArrayList<>();

        if (custom != null) {
            for (Map.Entry<String, Object> entry : jsonObject(custom.get("custom_params")).entrySet()) {
                if (!java.util.Objects.equals(nationalParams.get(entry.getKey()), entry.getValue())) {
                    overridden.add(entry.getKey());
                }
                effectiveParams.put(entry.getKey(), entry.getValue());
            }
        }
        if (!calculationPatch.isEmpty()) {
            overridden.add("calculation_definition");
        }

        String numerator = text(standard.get("numerator_rule"));
        String denominator = text(standard.get("denominator_rule"));
        String filterRule = text(standard.get("filter_rule"));
        String excludeRule = text(standard.get("exclude_rule"));
        String sql = text(standard.get("standard_sql"));
        if (custom != null) {
            numerator = override(custom, "custom_numerator", "numerator_rule", numerator, overridden);
            denominator = override(custom, "custom_denominator", "denominator_rule", denominator, overridden);
            filterRule = override(custom, "custom_filter", "filter_rule", filterRule, overridden);
            excludeRule = override(custom, "exclude_rule", "exclude_rule", excludeRule, overridden);
            sql = override(custom, "custom_sql", "standard_sql", sql, overridden);
        }

        String name = text(standard.get("index_name"));
        String nationalNumerator = text(standard.get("numerator_rule"));
        String nationalDenominator = text(standard.get("denominator_rule"));
        String definition = text(standard.get("index_desc"));
        if (custom != null && (!numerator.equals(nationalNumerator) || !denominator.equals(nationalDenominator))) {
            definition = numerator + "占" + denominator + "的比例。";
        }

        Map<String, Object> nationalRule = new LinkedHashMap<>();
        nationalRule.put("definition", text(standard.get("index_desc")));
        nationalRule.put("formula", formula(name, nationalNumerator, nationalDenominator));
        nationalRule.put("version", text(standard.get("version")));
        nationalRule.put("source_path", text(standard.get("source_path")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", indexCode);
        result.put("index_code", indexCode);
        result.put("rule_name", name);
        result.put("category", text(standard.get("index_type")));
        result.put("hospital_id", hospitalId);
        result.put("effective_level", custom == null ? "national" : "hospital");
        result.put("definition", definition);
        result.put("formula", formula(name, numerator, denominator));
        result.put("numerator_rule", numerator);
        result.put("denominator_rule", denominator);
        result.put("filter_rule", filterRule);
        result.put("exclude_rule", excludeRule);
        result.put("implementation_status", sql);
        result.put("standard_sql", sql);
        result.put("calculation_definition", calculationDefinition);
        result.put("national_calculation_definition", nationalCalculation);
        result.put("field_contract", jsonObject(standard.get("rely_table_field")));
        result.put("field_status", "configured");
        result.put("sql_status", sql.isEmpty() ? "unavailable" : "available");
        result.put("hospital_override", custom);
        result.put("national_rule", nationalRule);
        result.put("national_params", nationalParams);
        result.put("effective_params", effectiveParams);
        result.put("national_version", text(standard.get("version")));
        result.put("hospital_version", custom == null ? null : integer(custom.get("version")));
        result.put("overridden_fields", new ArrayList<>(new LinkedHashSet<>(overridden)));
        result.put("fallback_chain", List.of("hospital", "national"));
        result.put("rule_source", "mysql");
        result.put("warnings", List.of());
        result.put("relations", Map.of());
        return result;
    }

    /** 只生成字段级差异预览；不提交、审批、发布或写入规则。 */
    public Map<String, Object> previewChange(
            String ruleId, String hospitalId, String changeDescription) {
        Map<String, Object> effective = effectiveRule(ruleId, hospitalId);
        String currentDefinition = text(effective.get("definition"));
        String currentFormula = text(effective.get("formula"));
        String requestedDefinition = deriveFeedbackValue(currentDefinition, changeDescription);
        String requestedFormula = deriveFeedbackValue(currentFormula, changeDescription);
        List<Map<String, Object>> fieldChanges = List.of(
                fieldChange("指标定义", requestedDefinition, currentDefinition),
                fieldChange("计算公式", requestedFormula, currentFormula),
                fieldChange("实现状态", "", text(effective.get("sql_status"))));
        List<String> changedFields = fieldChanges.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("changed")))
                .map(item -> text(item.get("field"))).toList();
        boolean affectsDefinition = changedFields.contains("指标定义");
        boolean affectsFormula = changedFields.contains("计算公式");
        boolean requiresFieldReview = changedFields.contains("实现状态");

        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("level", "hospital");
        requested.put("status", "requested");
        requested.put("definition", requestedDefinition);
        requested.put("formula", requestedFormula);
        requested.put("source_text", changeDescription);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("level", text(effective.get("effective_level")));
        current.put("status", "effective");
        current.put("definition", currentDefinition);
        current.put("formula", currentFormula);
        current.put("implementation_status", text(effective.get("sql_status")));

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("changed_fields", changedFields);
        impact.put("affects_definition", affectsDefinition);
        impact.put("affects_formula", affectsFormula);
        impact.put("requires_field_review", requiresFieldReview);
        impact.put("requires_sql_regeneration", affectsFormula || requiresFieldReview);
        impact.put("requires_version_increment", !changedFields.isEmpty());

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("rule_id", text(effective.get("rule_id")));
        preview.put("rule_name", text(effective.get("rule_name")));
        preview.put("target_level", "hospital");
        preview.put("current_effective_level", text(effective.get("effective_level")));
        preview.put("requested", requested);
        preview.put("current_effective", current);
        preview.put("field_changes", fieldChanges);
        preview.put("impact", impact);
        preview.put("message", "检测到本院口径反馈，请确认差异后再提交变更申请。");
        return preview;
    }

    private static Map<String, Object> fieldChange(
            String field, String requested, String current) {
        return Map.of(
                "field", field,
                "requested", requested,
                "current", current,
                "changed", !requested.isBlank() && !requested.equals(current));
    }

    private static String deriveFeedbackValue(String base, String feedback) {
        String requested = feedback == null ? "" : feedback.strip();
        Matcher feedbackMinutes = MINUTES.matcher(requested);
        Matcher baseMinutes = MINUTES.matcher(base == null ? "" : base);
        if (feedbackMinutes.find() && baseMinutes.find()) {
            return baseMinutes.replaceFirst(Matcher.quoteReplacement(feedbackMinutes.group(1) + "分钟"));
        }
        return requested.isBlank() ? text(base) : requested;
    }

    public Map<String, Object> fieldMapping(String ruleId, String hospitalId) {
        List<Map<String, Object>> items = jdbc.query(
                "SELECT business_field,db_name,table_name,column_name,data_type,status "
                        + "FROM med_field_mapping WHERE hospital_id=? AND rule_id=? ORDER BY id",
                RuleReadRepository::rowMap,
                hospitalId, ruleId);
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            fields.put(text(item.get("business_field")),
                    text(item.get("table_name")) + "." + text(item.get("column_name")));
        }
        Map<String, Object> first = items.isEmpty() ? Map.of() : items.get(0);
        String dbName = text(first.get("db_name"));
        String dialect = dbName.toUpperCase().startsWith("WIN60_") ? "sqlserver" : "mysql";
        boolean confirmed = !items.isEmpty()
                && items.stream().allMatch(item -> "confirmed".equals(text(item.get("status"))));
        List<Map<String, Object>> relations = items.isEmpty() ? List.of() : jdbc.query(
                "SELECT left_table,left_column,right_table,right_column,join_type,relation_source,status "
                        + "FROM med_table_relation WHERE hospital_id=? AND db_name=? AND status='confirmed' ORDER BY id",
                RuleReadRepository::rowMap,
                hospitalId, dbName);
        List<Map<String, Object>> metadataItems = items.isEmpty() ? List.of() : jdbc.query(
                "SELECT mapping.business_field,mapping.table_name,mapping.column_name,"
                        + "mapping.data_type AS mapping_data_type,metadata.data_type AS metadata_data_type "
                        + "FROM med_field_mapping mapping LEFT JOIN med_metadata_column metadata "
                        + "ON metadata.hospital_id=mapping.hospital_id AND metadata.db_name=mapping.db_name "
                        + "AND metadata.table_name=mapping.table_name AND metadata.column_name=mapping.column_name "
                        + "WHERE mapping.hospital_id=? AND mapping.rule_id=? ORDER BY mapping.id",
                RuleReadRepository::rowMap,
                hospitalId, ruleId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", ruleId);
        result.put("hospital_id", hospitalId);
        result.put("dialect", dialect);
        result.put("db_name", dbName);
        result.put("schema", "sqlserver".equals(dialect) ? "WINDBA" : "");
        result.put("main_table", text(first.get("table_name")));
        result.put("fields", fields);
        result.put("status", confirmed ? "confirmed" : items.isEmpty() ? "missing" : "pending");
        result.put("items", items);
        result.put("relations", relations);
        result.put("metadata_items", metadataItems);
        result.put("query_profile", "MQSI2025_005".equals(ruleId) && "sqlserver".equals(dialect)
                ? "urgent_consult_sqlserver"
                : "MQSI2025_001".equals(ruleId) && "sqlserver".equals(dialect)
                        ? "inpatient_transfer_48h_sqlserver" : "");
        return result;
    }

    private List<Map<String, Object>> searchStandard(String query, String pattern, int limit) {
        return jdbc.query(
                "SELECT index_code,index_name,index_type,index_desc FROM med_index_standard "
                        + "WHERE status=1 AND (index_code=? OR index_name=? OR index_name LIKE ? OR index_desc LIKE ?) "
                        + "ORDER BY CASE WHEN index_code=? OR index_name=? THEN 0 ELSE 1 END,index_code LIMIT ?",
                (rs, rowNum) -> match(rs, "mysql_standard"),
                query, query, pattern, pattern, query, query, limit);
    }

    private List<Map<String, Object>> searchLocalCompact(
            String query, String hospitalId, LocalDateTime now, int limit) {
        return jdbc.query(
                "SELECT index_code,index_name,index_type,index_desc FROM med_index_hospital_defined "
                        + "WHERE hospital_id=? AND status=1 AND approval_status='approved' "
                        + "AND (effective_from IS NULL OR effective_from<=?) "
                        + "AND (effective_to IS NULL OR effective_to>=?) "
                        + "AND REPLACE(REPLACE(REPLACE(index_name,' ',''),'　',''),'的','') LIKE ? "
                        + "ORDER BY index_code LIMIT ?",
                (rs, rowNum) -> match(rs, "mysql_hospital_defined"),
                hospitalId, now, now, "%" + query + "%", limit);
    }

    private List<Map<String, Object>> searchStandardCompact(String query, int limit) {
        return jdbc.query(
                "SELECT index_code,index_name,index_type,index_desc FROM med_index_standard "
                        + "WHERE status=1 AND REPLACE(REPLACE(REPLACE(index_name,' ',''),'　',''),'的','') LIKE ? "
                        + "ORDER BY index_code LIMIT ?",
                (rs, rowNum) -> match(rs, "mysql_standard"),
                "%" + query + "%", limit);
    }

    private Map<String, Object> findStandard(String query) {
        String normalized = query == null ? "" : query.strip();
        List<Map<String, Object>> exact = jdbc.query(
                "SELECT * FROM med_index_standard WHERE status=1 AND (index_code=? OR index_name=?) "
                        + "ORDER BY CASE WHEN index_code=? THEN 0 ELSE 1 END LIMIT 1",
                RuleReadRepository::rowMap,
                normalized, normalized, normalized);
        if (!exact.isEmpty()) {
            return exact.get(0);
        }
        List<Map<String, Object>> fuzzy = jdbc.query(
                "SELECT * FROM med_index_standard WHERE status=1 AND index_name LIKE ? ORDER BY index_code LIMIT 1",
                RuleReadRepository::rowMap,
                "%" + normalized + "%");
        return fuzzy.isEmpty() ? null : fuzzy.get(0);
    }

    private Map<String, Object> findCustom(String hospitalId, String indexCode) {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM med_index_hospital_custom WHERE hospital_id=? AND index_code=? "
                        + "AND status=1 AND approval_status='approved' "
                        + "AND (effective_from IS NULL OR effective_from<=?) "
                        + "AND (effective_to IS NULL OR effective_to>?) LIMIT 1",
                RuleReadRepository::rowMap,
                hospitalId, indexCode, now, now);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> findDefined(String query, String hospitalId) {
        String normalized = query == null ? "" : query.strip();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> exact = jdbc.query(
                "SELECT * FROM med_index_hospital_defined WHERE hospital_id=? AND status=1 "
                        + "AND approval_status='approved' AND (effective_from IS NULL OR effective_from<=?) "
                        + "AND (effective_to IS NULL OR effective_to>=?) AND (index_code=? OR index_name=?) "
                        + "ORDER BY CASE WHEN index_code=? THEN 0 ELSE 1 END LIMIT 1",
                RuleReadRepository::rowMap,
                hospitalId, now, now, normalized, normalized, normalized);
        if (!exact.isEmpty()) {
            return exact.get(0);
        }
        List<Map<String, Object>> fuzzy = jdbc.query(
                "SELECT * FROM med_index_hospital_defined WHERE hospital_id=? AND status=1 "
                        + "AND approval_status='approved' AND (effective_from IS NULL OR effective_from<=?) "
                        + "AND (effective_to IS NULL OR effective_to>=?) AND index_name LIKE ? ORDER BY index_code LIMIT 1",
                RuleReadRepository::rowMap,
                hospitalId, now, now, "%" + normalized + "%");
        return fuzzy.isEmpty() ? null : fuzzy.get(0);
    }

    private Map<String, Object> definedEffectiveRule(Map<String, Object> item, String hospitalId) {
        String name = text(item.get("index_name"));
        String numerator = text(item.get("numerator_rule"));
        String denominator = text(item.get("denominator_rule"));
        String sql = text(item.get("sql_template"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", text(item.get("index_code")));
        result.put("index_code", text(item.get("index_code")));
        result.put("rule_name", name);
        result.put("category", text(item.get("index_type")));
        result.put("hospital_id", hospitalId);
        result.put("effective_level", "hospital_defined");
        result.put("definition", text(item.get("index_desc")));
        result.put("formula", formula(name, numerator, denominator));
        result.put("numerator_rule", numerator);
        result.put("denominator_rule", denominator);
        result.put("filter_rule", text(item.get("filter_rule")));
        result.put("exclude_rule", text(item.get("exclude_rule")));
        result.put("implementation_status", sql);
        result.put("standard_sql", sql);
        result.put("calculation_definition", jsonObject(item.get("calculation_definition")));
        result.put("national_calculation_definition", Map.of());
        result.put("field_contract", jsonValue(item.get("field_contract"), List.of()));
        result.put("field_status", "configured");
        result.put("sql_status", sql.isEmpty() ? "unavailable" : "available");
        result.put("hospital_override", null);
        result.put("national_rule", Map.of());
        result.put("national_params", Map.of());
        result.put("effective_params", jsonObject(item.get("rule_params")));
        result.put("national_version", null);
        result.put("hospital_version", integer(item.get("version")));
        result.put("overridden_fields", List.of());
        result.put("fallback_chain", List.of("hospital_defined"));
        result.put("rule_source", "mysql");
        result.put("warnings", List.of());
        result.put("relations", Map.of());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeCalculationPatch(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> merged = (Map<String, Object>) deepCopy(base);
        if (patch.isEmpty()) {
            return merged;
        }
        if (patch.containsKey("schema_version")) {
            throw new IllegalArgumentException("医院计算补丁不允许修改 schema_version");
        }
        Set<String> unknown = new LinkedHashSet<>(patch.keySet());
        unknown.removeAll(PATCH_ROOTS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("医院计算补丁包含未知节点：" + String.join("、", unknown));
        }
        mergeNode(merged, patch);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private void mergeNode(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("医院计算补丁不允许删除计算定义节点 " + entry.getKey());
            }
            Object current = target.get(entry.getKey());
            if (current instanceof Map<?, ?> currentMap && entry.getValue() instanceof Map<?, ?> patchMap) {
                mergeNode((Map<String, Object>) currentMap, (Map<String, Object>) patchMap);
            } else {
                target.put(entry.getKey(), deepCopy(entry.getValue()));
            }
        }
    }

    private Object deepCopy(Object value) {
        return objectMapper.convertValue(value, Object.class);
    }

    private Map<String, Object> jsonObject(Object value) {
        Object parsed = jsonValue(value, Map.of());
        if (!(parsed instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Object jsonValue(Object value, Object fallback) {
        if (value == null || "".equals(value)) {
            return fallback;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return value;
        }
        try {
            String text = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
            JsonNode node = objectMapper.readTree(text);
            return objectMapper.convertValue(node, Object.class);
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static Map<String, Object> match(ResultSet rs, String type) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", rs.getString("index_code"));
        result.put("rule_name", rs.getString("index_name"));
        result.put("category", rs.getString("index_type"));
        result.put("content", rs.getString("index_desc"));
        result.put("type", type);
        return result;
    }

    private static Map<String, Object> rowMap(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            Object value = rs.getObject(index);
            if (value instanceof Timestamp timestamp) {
                value = timestamp.toLocalDateTime();
            }
            result.put(meta.getColumnLabel(index).toLowerCase(), value);
        }
        return result;
    }

    private static String override(
            Map<String, Object> custom,
            String customKey,
            String resultKey,
            String current,
            List<String> overridden) {
        String candidate = text(custom.get(customKey)).strip();
        if (candidate.isEmpty()) {
            return current;
        }
        overridden.add(resultKey);
        return candidate;
    }

    private static String formula(String name, String numerator, String denominator) {
        return name + " = (" + numerator + " / " + denominator + ") × 100%";
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String compact(String value) {
        return value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && codePoint != '的')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}

package com.hospital.wikiagent.implementation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 将结构化输入渲染为 {@code DraftSqlPlanRenderer} 所需的确定性结果。
 *
 * <p>输出由结构化输入确定性生成，禁止拼接未校验的标识符或执行任意 SQL。生成结果必须保留来源对象和版本，便于审计与复现。</p>
 */
@Component
public class DraftSqlPlanRenderer {
    private static final Set<String> OPERATORS = Set.of(
            "eq", "ne", "gt", "gte", "lt", "lte", "in", "not_in",
            "is_null", "not_null", "minutes_between_lte");

    public RenderedSql render(Map<String, Object> plan, Map<String, Object> mappings) {
        String mainTable = identifier(text(plan.get("main_table")));
        String metricType = text(plan.get("metric_type"));
        if (!Set.of("ratio", "count").contains(metricType)) {
            throw invalid("计算类型必须是 ratio 或 count。");
        }
        String subjectField = required(plan, "subject_field");
        String timeField = required(plan, "time_field");
        String hospitalField = text(plan.get("hospital_field"));
        if (hospitalField.isBlank()) hospitalField = "hospital_id";
        List<Map<String, Object>> numerator = conditions(plan.get("numerator_conditions"));
        List<Map<String, Object>> denominator = conditions(plan.get("denominator_conditions"));

        Set<String> referenced = new LinkedHashSet<>();
        referenced.add(subjectField); referenced.add(timeField); referenced.add(hospitalField);
        for (Map<String, Object> condition : joined(numerator, denominator)) {
            referenced.add(required(condition, "field"));
            String compare = text(condition.get("compare_field"));
            if (!compare.isBlank()) referenced.add(compare);
        }
        Map<String, String> columns = new LinkedHashMap<>();
        for (String field : referenced) {
            Map<String, Object> mapping = map(mappings.get(field));
            if (mapping.isEmpty()) throw invalid("字段尚未确认：" + field);
            if (!mainTable.equals(text(mapping.get("table_name")))) {
                throw invalid("第一版 SQL 计划只能使用单一主表。");
            }
            columns.put(field, identifier(text(mapping.get("column_name"))));
        }
        ConditionSql denominatorSql = compile(denominator, columns, "den");
        ConditionSql numeratorOnly = compile(numerator, columns, "num");
        String numeratorSql = and(denominatorSql.sql(), numeratorOnly.sql());
        String subject = columns.get(subjectField);
        String where = columns.get(hospitalField) + " = :hospital_id\n"
                + "  AND " + columns.get(timeField) + " >= :start_time\n"
                + "  AND " + columns.get(timeField) + " < :end_time";
        String sql;
        if ("ratio".equals(metricType)) {
            String num = "COUNT(DISTINCT CASE WHEN " + numeratorSql + " THEN " + subject + " END)";
            String den = "COUNT(DISTINCT CASE WHEN " + denominatorSql.sql() + " THEN " + subject + " END)";
            sql = "SELECT\n"
                    + "  CASE WHEN " + den + " = 0 THEN CAST(0 AS decimal(10,2))\n"
                    + "       ELSE CAST(" + num + " * 100.0 / " + den + " AS decimal(10,2)) END AS index_value,\n"
                    + "  " + num + " AS numerator_count,\n"
                    + "  " + den + " AS denominator_count,\n"
                    + "  " + den + " AS sample_count\n"
                    + "FROM " + mainTable + "\nWHERE " + where;
        } else {
            String count = "COUNT(DISTINCT CASE WHEN " + numeratorSql + " THEN " + subject + " END)";
            sql = "SELECT\n  " + count + " AS index_value,\n  " + count + " AS numerator_count,\n"
                    + "  " + count + " AS denominator_count,\n  " + count + " AS sample_count\n"
                    + "FROM " + mainTable + "\nWHERE " + where;
        }
        Map<String, Object> params = new LinkedHashMap<>(denominatorSql.params());
        params.putAll(numeratorOnly.params());
        return new RenderedSql(sql, params, mainTable, metricType, "sqlserver");
    }

    private ConditionSql compile(List<Map<String, Object>> conditions, Map<String, String> columns, String prefix) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index < conditions.size(); index++) {
            Map<String, Object> condition = conditions.get(index);
            String field = required(condition, "field");
            String operator = required(condition, "operator");
            if (!OPERATORS.contains(operator)) throw invalid("不支持的条件操作符：" + operator);
            String column = columns.get(field);
            String base = prefix + "_" + index;
            Object value = condition.get("value");
            switch (operator) {
                case "is_null" -> clauses.add(column + " IS NULL");
                case "not_null" -> clauses.add(column + " IS NOT NULL");
                case "in", "not_in" -> {
                    if (!(value instanceof List<?> values) || values.isEmpty()) {
                        throw invalid(operator + " 条件必须提供非空列表。");
                    }
                    List<String> names = new ArrayList<>();
                    for (int valueIndex = 0; valueIndex < values.size(); valueIndex++) {
                        String name = base + "_" + valueIndex;
                        params.put(name, values.get(valueIndex));
                        names.add(":" + name);
                    }
                    clauses.add(column + ("in".equals(operator) ? " IN (" : " NOT IN (")
                            + String.join(", ", names) + ")");
                }
                case "minutes_between_lte" -> {
                    String compare = text(condition.get("compare_field"));
                    if (compare.isBlank() || !columns.containsKey(compare)
                            || !(value instanceof Number number) || number.doubleValue() < 0) {
                        throw invalid("minutes_between_lte 条件缺少有效比较字段或分钟数。");
                    }
                    params.put(base, value);
                    clauses.add("DATEDIFF(MINUTE, " + columns.get(compare) + ", " + column
                            + ") BETWEEN 0 AND :" + base);
                }
                default -> {
                    if (value == null) throw invalid(operator + " 条件缺少值。");
                    params.put(base, value);
                    clauses.add(column + " " + symbol(operator) + " :" + base);
                }
            }
        }
        return new ConditionSql(clauses.isEmpty() ? "1=1" : String.join(" AND ", clauses), params);
    }

    private static String symbol(String operator) {
        return switch (operator) {
            case "eq" -> "="; case "ne" -> "<>"; case "gt" -> ">"; case "gte" -> ">=";
            case "lt" -> "<"; case "lte" -> "<=";
            default -> throw invalid("不支持的比较操作符。");
        };
    }

    private static String identifier(String value) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) throw invalid("非法标识符：" + value);
        return value;
    }

    private static String required(Map<String, Object> value, String key) {
        String result = text(value.get(key));
        if (result.isBlank()) throw invalid("SQL 计算计划缺少 " + key + "。");
        return result;
    }

    private static String and(String left, String right) {
        if ("1=1".equals(left)) return right;
        if ("1=1".equals(right)) return left;
        return left + " AND " + right;
    }

    private static List<Map<String, Object>> joined(
            List<Map<String, Object>> left, List<Map<String, Object>> right) {
        List<Map<String, Object>> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> conditions(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static String text(Object value) { return value == null ? "" : value.toString().strip(); }
    private static ImplementationException invalid(String message) {
        return new ImplementationException("DRAFT_SQL_PLAN_INVALID", message, 400);
    }

    public record RenderedSql(
            String sql, Map<String, Object> params, String mainTable, String metricType, String dialect) { }
    private record ConditionSql(String sql, Map<String, Object> params) { }
}

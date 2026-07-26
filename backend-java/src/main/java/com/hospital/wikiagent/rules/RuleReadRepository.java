package com.hospital.wikiagent.rules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 核心制度规则只读仓储。
 *
 * <p>指标定义、口径、Profile 和 SQL 引用只从版本化 HXZD Wiki 机器契约读取。
 * SQLite 仅用于补充已经同步的数据库元数据类型，不能再作为规则、草稿或发布数据源。
 * 这一边界保证部署时无需 MySQL，也避免已废弃 MQSI 表与新 Wiki 内容混用。</p>
 */
@Repository
public class RuleReadRepository {
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*分钟");

    private final JdbcTemplate jdbc;
    private final WikiRuleKnowledgeSource wiki;

    @Autowired
    public RuleReadRepository(JdbcTemplate jdbc, WikiRuleKnowledgeSource wiki) {
        this.jdbc = jdbc;
        this.wiki = wiki;
    }

    /**
     * 测试兼容构造器也只读取 HXZD Wiki，不恢复旧 MQSI/JDBC 规则分支。
     * Maven 从 backend-java 目录运行时，知识库位于相邻的 core-rules-wiki。
     */
    public RuleReadRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, new WikiRuleKnowledgeSource(defaultWikiRoot(), objectMapper));
    }

    /** 允许测试显式注入临时 Wiki 根目录；ObjectMapper 参数仅保留旧测试签名。 */
    public RuleReadRepository(
            JdbcTemplate jdbc, ObjectMapper objectMapper, WikiRuleKnowledgeSource wiki) {
        this(jdbc, wiki);
    }

    /** 搜索当前医院可见的 HXZD 指标。 */
    public Map<String, Object> searchForHospital(String query, String hospitalId, int limit) {
        return wiki.searchForHospital(query, hospitalId, limit);
    }

    /** 返回当前 Wiki 中处于 active 状态的指标名称和编号。 */
    public List<Map<String, String>> activeIndicatorNames(String hospitalId, int limit) {
        return wiki.activeIndicatorNames(hospitalId, limit);
    }

    /** 读取指标默认 Profile；Profile 即使不可执行也可用于口径解释。 */
    public Map<String, Object> effectiveRule(String query, String hospitalId) {
        return wiki.effectiveRule(query, hospitalId);
    }

    /** 读取同一指标下明确指定的 Profile，禁止跨 Profile 拼接字段和 SQL。 */
    public Map<String, Object> effectiveRule(
            String query, String hospitalId, String profileId) {
        return wiki.effectiveRule(query, hospitalId, profileId);
    }

    /** 返回已经审批且通过执行门禁的诊断候选口径。 */
    public List<Map<String, Object>> diagnosticProfiles(String ruleId, String hospitalId) {
        return wiki.diagnosticProfiles(ruleId, hospitalId);
    }

    /** 返回已经审批且通过执行门禁的普通候选口径。 */
    public List<Map<String, Object>> caliberProfiles(String ruleId, String hospitalId) {
        return wiki.caliberProfiles(ruleId, hospitalId);
    }

    /**
     * 返回指标下全部可见 Profile 的展示目录。
     *
     * <p>该目录同时包含当前口径、可试算候选、仅可解释候选和草稿。它只用于回答
     * “还有哪些口径”及生成用户选择项；真正执行时仍必须重新经过
     * {@link #caliberProfiles(String, String)} 的审批与 SQL 门禁。</p>
     */
    public List<Map<String, Object>> caliberCatalog(String ruleId, String hospitalId) {
        return wiki.caliberCatalog(ruleId, hospitalId);
    }

    /** 返回 Wiki 中允许列表式的数据质量规则，不接受用户提供任意 SQL。 */
    public List<Map<String, Object>> dataQualityRules(String ruleId) {
        return wiki.dataQualityRules(ruleId);
    }

    /**
     * 生成只读字段级差异预览。
     *
     * <p>该能力不会创建草稿、提交审批或发布规则；已废弃的治理工作台不再有任何写入口。</p>
     */
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
                fieldChange("实现状态", "", text(effective.get("execution_status"))));
        List<String> changedFields = fieldChanges.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("changed")))
                .map(item -> text(item.get("field")))
                .toList();

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
        current.put("execution_status", text(effective.get("execution_status")));

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("changed_fields", changedFields);
        impact.put("affects_definition", changedFields.contains("指标定义"));
        impact.put("affects_formula", changedFields.contains("计算公式"));
        impact.put("requires_field_review", changedFields.contains("实现状态"));
        impact.put("requires_sql_regeneration",
                changedFields.contains("计算公式") || changedFields.contains("实现状态"));
        impact.put("requires_version_increment", !changedFields.isEmpty());

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("rule_id", text(effective.get("rule_id")));
        preview.put("rule_name", text(effective.get("rule_name")));
        preview.put("profile_id", text(effective.get("profile_id")));
        preview.put("target_level", "hospital");
        preview.put("current_effective_level", text(effective.get("effective_level")));
        preview.put("requested", requested);
        preview.put("current_effective", current);
        preview.put("field_changes", fieldChanges);
        preview.put("impact", impact);
        preview.put("message", "已生成只读差异预览；当前系统不提供草稿、审批或发布功能。");
        return preview;
    }

    public Map<String, Object> fieldMapping(String ruleId, String hospitalId) {
        return fieldMapping(ruleId, hospitalId, null);
    }

    /**
     * 读取指定 Profile 的字段契约，并用本地 SQLite 中最近同步的元数据补充实际类型。
     * 元数据不存在时保留空类型，由 SQL 准备门禁判定是否可执行。
     */
    public Map<String, Object> fieldMapping(
            String ruleId, String hospitalId, String profileId) {
        Map<String, Object> result = new LinkedHashMap<>(
                wiki.fieldMapping(ruleId, hospitalId, profileId));
        List<Map<String, Object>> metadataItems = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(result.get("items"))) {
            Map<String, Object> enriched = new LinkedHashMap<>(item);
            String actualType = jdbc.query(
                    "SELECT data_type FROM med_metadata_column "
                            + "WHERE hospital_id=? AND db_name=? AND table_name=? AND column_name=? "
                            + "ORDER BY id DESC LIMIT 1",
                    rows -> rows.next() ? text(rows.getObject(1)) : "",
                    hospitalId, text(item.get("db_name")), text(item.get("table_name")),
                    text(item.get("column_name")));
            enriched.put("mapping_data_type", text(item.get("data_type")));
            enriched.put("metadata_data_type", actualType);
            metadataItems.add(enriched);
        }
        result.put("metadata_items", metadataItems);
        return result;
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
            return baseMinutes.replaceFirst(
                    Matcher.quoteReplacement(feedbackMinutes.group(1) + "分钟"));
        }
        return requested.isBlank() ? text(base) : requested;
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> source)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, nested) -> copy.put(String.valueOf(key), nested));
            result.add(copy);
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String defaultWikiRoot() {
        Path direct = Path.of("core-rules-wiki");
        return Files.isDirectory(direct) ? direct.toString() : Path.of("..", "core-rules-wiki").toString();
    }
}

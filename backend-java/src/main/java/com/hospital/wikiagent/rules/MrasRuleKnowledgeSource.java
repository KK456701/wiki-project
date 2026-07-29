package com.hospital.wikiagent.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.mras.ConceptPageParser;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;

/**
 * 基于领导知识库（knowledge-index）的规则知识源适配器。
 *
 * <p>完全替代旧 core-rules-wiki 数据源，对外暴露与 WikiRuleKnowledgeSource 相同的
 * 公共 API，内部从 EntityPageParser + ConceptPageParser 取数据。下游 10+ 个消费类
 * （ToolRegistry、BatchIndicatorRuntime、IndicatorSqlTools 等）代码不变。</p>
 */
@Component
@Primary
public class MrasRuleKnowledgeSource extends WikiRuleKnowledgeSource {

    private static final Logger log = LoggerFactory.getLogger(MrasRuleKnowledgeSource.class);
    private static final Pattern NUMERATOR_PATTERN = Pattern.compile("分子[：:]\\s*(.+)");
    private static final Pattern DENOMINATOR_PATTERN = Pattern.compile("分母[：:]\\s*(.+)");

    private final EntityPageParser entityParser;
    private final ConceptPageParser conceptParser;
    private final MrasTemplateRenderer templateRenderer;

    public MrasRuleKnowledgeSource(
            EntityPageParser entityParser,
            ConceptPageParser conceptParser,
            MrasTemplateRenderer templateRenderer) {
        super();
        this.entityParser = entityParser;
        this.conceptParser = conceptParser;
        this.templateRenderer = templateRenderer;
        log.info("MrasRuleKnowledgeSource 初始化完成: {} 个实体, {} 个概念页",
                entityParser.size(), conceptParser.size());
    }

    @Override
    public Map<String, Object> searchForHospital(String query, String hospitalId, int limit) {
        String normalized = normalize(query);
        List<Map.Entry<Integer, EntityPageData>> scored = new ArrayList<>();
        // 按基础编码去重搜索（避免同一指标多变体重复出现）
        Set<String> seen = new LinkedHashSet<>();
        for (EntityPageData entity : entityParser.getAllEntities().values()) {
            if (!seen.add(entity.code())) continue;
            int score = score(normalized, entity);
            if (score > 0) {
                scored.add(Map.entry(score, entity));
            }
        }
        List<Map<String, Object>> matches = scored.stream()
                .sorted(Comparator.<Map.Entry<Integer, EntityPageData>>comparingInt(Map.Entry::getKey).reversed()
                        .thenComparing(e -> e.getValue().code()))
                .limit(Math.max(1, limit))
                .map(e -> matchCard(e.getValue()))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query == null ? "" : query.strip());
        result.put("hospital_id", hospitalId);
        result.put("resolved_rule_id", matches.isEmpty() ? null : matches.get(0).get("rule_id"));
        result.put("matches", matches);
        result.put("rule_source", "mras");
        result.put("knowledge_release_id", "mras-v60");
        return result;
    }

    @Override
    public List<Map<String, String>> activeIndicatorNames(String hospitalId, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (EntityPageData entity : entityParser.getAllEntities().values()) {
            if (!seen.add(entity.code())) continue;
            result.add(Map.of("rule_id", entity.code(), "rule_name", entity.name()));
            if (result.size() >= Math.max(1, Math.min(500, limit))) break;
        }
        return result;
    }

    @Override
    public Map<String, Object> effectiveRule(String query, String hospitalId) {
        return effectiveRule(query, hospitalId, null);
    }

    @Override
    public Map<String, Object> effectiveRule(String query, String hospitalId, String profileId) {
        EntityPageData entity = resolveEntity(query, profileId);
        if (entity == null) {
            throw new RuleNotFoundException("RULE_NOT_FOUND: " + query);
        }
        ConceptPageParser.ConceptPageData concept = conceptParser.getConcept(entity.code());

        String definition = !entity.definition().isBlank() ? entity.definition()
                : (concept != null ? concept.definition() : "");
        String formula = !entity.formula().isBlank() ? entity.formula()
                : (concept != null ? concept.formula() : "");
        String significance = concept != null ? concept.significance() : "";
        String unit = concept != null && !concept.unit().isBlank() ? concept.unit() : "percentage";

        String numeratorRule = extractPattern(formula, NUMERATOR_PATTERN);
        String denominatorRule = extractPattern(formula, DENOMINATOR_PATTERN);

        boolean hasSql = entity.hasOverviewSql();
        String executionStatus = hasSql ? "executable" : "documentation_only";
        // 概览 SQL 原文带实体页引号包裹与 #ETC/#EQUALS 模板标记，必须先按与批量执行
        // 链路同口径解析后再对外暴露，否则 Planner 路径的 IndicatorSqlTools 只读校验
        // 会因前导引号/未解析表达式拒绝（SQL_VALIDATION_FAILED）。
        String standardSql = hasSql ? renderOverviewSql(entity.overviewSql()) : "";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", entity.code());
        result.put("index_code", entity.code());
        result.put("rule_name", entity.name());
        result.put("category", entity.category());
        result.put("hospital_id", hospitalId);
        result.put("effective_level", "company");
        result.put("profile_id", entity.variantCode());
        result.put("profile_name", entity.variantLabel());
        result.put("execution_status", executionStatus);
        result.put("execution_blockers", List.of());
        result.put("definition", definition);
        result.put("formula", formula);
        result.put("numerator_rule", numeratorRule);
        result.put("denominator_rule", denominatorRule);
        result.put("filter_rule", "");
        result.put("exclude_rule", "");
        result.put("implementation_status", standardSql);
        result.put("standard_sql", standardSql);
        result.put("source_extract_sql", entity.sourceTableSql());
        result.put("department_detail_sql", entity.deptStatSql());
        result.put("patient_detail_sql", entity.patientDetailSql());
        result.put("sql_capabilities", Map.of(
                "overview", Map.of("status", hasSql ? "executable" : "unavailable")));
        result.put("extraction_contract", Map.of());
        result.put("dual_database_contract", Map.of());
        result.put("result_mapping", Map.of(
                "index_value", "监测情况",
                "numerator_count", "分子",
                "denominator_count", "分母"));
        result.put("result_contract", Map.of("unit", unit));
        result.put("overview_runtime_eligible", hasSql);
        result.put("calculation_definition", Map.of(
                "numerator", numeratorRule,
                "denominator", denominatorRule));
        result.put("national_calculation_definition", Map.of(
                "numerator", numeratorRule,
                "denominator", denominatorRule));
        result.put("field_contract", Map.of());
        result.put("field_status", "mras_default");
        result.put("sql_status", hasSql ? "available" : "unavailable");
        result.put("hospital_override", null);
        result.put("company_rule", Map.of(
                "path", "knowledge-index/entities/" + entity.variantCode(),
                "implementation", entity.variantLabel(),
                "implementation_status", executionStatus));
        result.put("national_rule", Map.of(
                "definition", definition,
                "formula", formula,
                "version", "2025",
                "source_path", "knowledge-index/concepts/" + entity.code() + entity.name() + ".md"));
        result.put("national_params", Map.of());
        result.put("effective_params", Map.of(
                "marptBeginAt", "统计开始时间",
                "marptEndAt", "统计结束时间"));
        result.put("result_unit", unit);
        result.put("national_version", "2025");
        result.put("hospital_version", null);
        result.put("overridden_fields", List.of());
        result.put("fallback_chain", List.of("company"));
        result.put("rule_source", "mras");
        result.put("knowledge_release_id", "mras-v60");
        result.put("warnings", List.of());
        result.put("relations", Map.of());
        result.put("significance", significance);
        result.put("system", entity.system());
        result.put("caliber", entity.caliber());
        result.put("data_source", entity.dataSource());
        result.put("monitor_params", entity.monitorParams());
        result.put("dimension", entity.dimension());
        return result;
    }

    @Override
    public Map<String, Object> fieldMapping(String ruleId, String hospitalId) {
        return fieldMapping(ruleId, hospitalId, null);
    }

    @Override
    public Map<String, Object> fieldMapping(String ruleId, String hospitalId, String profileId) {
        EntityPageData entity = resolveEntity(ruleId, profileId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", ruleId);
        result.put("profile_id", entity != null ? entity.variantCode() : ruleId);
        result.put("execution_status", entity != null && entity.hasOverviewSql() ? "executable" : "documentation_only");
        result.put("execution_blockers", List.of());
        result.put("hospital_id", hospitalId);
        result.put("status", "mras_default");
        result.put("dialect", "sqlserver");
        result.put("db_name", "WINDBA_GN");
        result.put("fields", Map.of());
        result.put("parameters", Map.of(
                "marptBeginAt", "统计开始时间",
                "marptEndAt", "统计结束时间"));
        result.put("relations", List.of());
        result.put("query_profile", "");
        result.put("items", List.of());
        result.put("metadata_items", List.of());
        result.put("rule_source", "mras");
        result.put("knowledge_release_id", "mras-v60");
        return result;
    }

    @Override
    public List<Map<String, Object>> caliberProfiles(String ruleId, String hospitalId) {
        List<EntityPageData> variants = entityParser.getVariants(ruleId);
        if (variants.isEmpty()) {
            // 尝试直接查找
            EntityPageData entity = entityParser.getEntity(ruleId);
            if (entity == null) {
                throw new RuleNotFoundException("RULE_NOT_FOUND: " + ruleId);
            }
            variants = List.of(entity);
        }
        return variants.stream()
                .filter(EntityPageData::hasOverviewSql)
                .map(entity -> {
                    Map<String, Object> profile = new LinkedHashMap<>();
                    profile.put("profile_id", entity.variantCode());
                    profile.put("profile_name", entity.variantLabel());
                    profile.put("label", entity.variantLabel());
                    profile.put("status", "approved");
                    profile.put("governance_status", "approved");
                    profile.put("execution_status", "executable");
                    profile.put("overview_runtime_eligible", true);
                    profile.put("parameter_overrides", Map.of());
                    profile.put("field_role_overrides", Map.of());
                    profile.put("numerator_rule", extractPattern(entity.formula(), NUMERATOR_PATTERN));
                    profile.put("denominator_rule", extractPattern(entity.formula(), DENOMINATOR_PATTERN));
                    profile.put("time_dimension", entity.dimension());
                    return profile;
                })
                .toList();
    }

    @Override
    public List<Map<String, Object>> caliberCatalog(String ruleId, String hospitalId) {
        return caliberProfiles(ruleId, hospitalId);
    }

    @Override
    public List<Map<String, Object>> diagnosticProfiles(String ruleId, String hospitalId) {
        return List.of();
    }

    @Override
    public List<Map<String, Object>> dataQualityRules(String ruleId) {
        return List.of();
    }

    // ─── 内部方法 ───────────────────────────────────────────────────────────

    /**
     * 按批量执行链路同口径预解析概览 SQL：#ETC/#EQUALS 条件裁剪 + 方言修正 +
     * 剥首尾引号；保留 :marptBeginAt/:marptEndAt 命名参数交给下游 SqlParameterBinder。
     * 条件判定参数与 MrasParameterMapper 默认行为一致：只提供时间参数，
     * hospitalAreaList/onlySearchFeilds 等可选参数缺失 → 对应 #ETC 行删除、#EQUALS 走假分支。
     */
    private String renderOverviewSql(String overviewSql) {
        try {
            String rendered = templateRenderer.renderTemplate(overviewSql, Map.of(
                    "marptBeginAt", "统计开始时间",
                    "marptEndAt", "统计结束时间"));
            return MrasSqlExecutionService.stripLeadingTrailingQuotes(rendered);
        } catch (RuntimeException exception) {
            log.warn("概览 SQL 模板预解析失败，回退原文: {}", exception.getMessage());
            return overviewSql;
        }
    }

    private EntityPageData resolveEntity(String query, String profileId) {
        // 精确编码匹配
        EntityPageData direct = entityParser.getEntity(query);
        if (direct != null) {
            // 如果指定了 profileId，尝试找对应变体
            if (profileId != null && !profileId.isBlank()) {
                List<EntityPageData> variants = entityParser.getVariants(direct.code());
                for (EntityPageData v : variants) {
                    if (v.variantCode().equals(profileId)) return v;
                }
            }
            return direct;
        }
        // 模糊名称匹配
        String normalized = normalize(query);
        EntityPageData best = null;
        int bestScore = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (EntityPageData entity : entityParser.getAllEntities().values()) {
            if (!seen.add(entity.variantCode())) continue;
            int s = score(normalized, entity);
            if (s > bestScore) {
                bestScore = s;
                best = entity;
            }
        }
        return best;
    }

    private int score(String query, EntityPageData entity) {
        if (query.isBlank()) return 0;
        List<String> candidates = List.of(
                entity.code(), entity.name(), entity.system(), entity.category());
        int result = 0;
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (normalized.isBlank()) continue;
            if (query.equals(normalized)) {
                result = Math.max(result, 100);
            } else if (query.contains(normalized) || normalized.contains(query)) {
                result = Math.max(result, 70 + Math.min(20, normalized.length()));
            } else {
                Set<Integer> left = normalized.codePoints().boxed()
                        .collect(java.util.stream.Collectors.toSet());
                Set<Integer> right = query.codePoints().boxed()
                        .collect(java.util.stream.Collectors.toSet());
                Set<Integer> overlap = new LinkedHashSet<>(left);
                overlap.retainAll(right);
                int smaller = Math.min(left.size(), right.size());
                if (smaller > 0 && overlap.size() >= 4) {
                    result = Math.max(result, overlap.size() * 60 / smaller);
                }
            }
        }
        return result >= 35 ? result : 0;
    }

    private Map<String, Object> matchCard(EntityPageData entity) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("rule_id", entity.code());
        card.put("rule_name", entity.name());
        card.put("category", entity.category());
        card.put("content", entity.definition());
        card.put("formula", entity.formula());
        card.put("numerator", extractPattern(entity.formula(), NUMERATOR_PATTERN));
        card.put("denominator", extractPattern(entity.formula(), DENOMINATOR_PATTERN));
        card.put("time_dimension", entity.dimension());
        card.put("execution_status", entity.hasOverviewSql() ? "executable" : "documentation_only");
        card.put("type", "wiki_rule");
        card.put("path", "knowledge-index/entities/" + entity.variantCode() + ".md");
        return card;
    }

    private static String extractPattern(String text, Pattern pattern) {
        if (text == null || text.isBlank()) return "";
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).strip() : "";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s　，。？！、：；（）()《》\"'`]+", "")
                .replace("的", "");
    }
}

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
 * 基于知识库（knowledge-index）的规则知识源适配器。
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
        result.put("hospitalId", hospitalId);
        result.put("resolvedRuleId", matches.isEmpty() ? null : matches.get(0).get("ruleId"));
        result.put("matches", matches);
        result.put("ruleSource", "mras");
        result.put("knowledgeReleaseId", "mras-v60");
        return result;
    }

    @Override
    public List<Map<String, String>> activeIndicatorNames(String hospitalId, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (EntityPageData entity : entityParser.getAllEntities().values()) {
            if (!seen.add(entity.code())) continue;
            result.add(Map.of("ruleId", entity.code(), "ruleName", entity.name()));
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
        result.put("ruleId", entity.code());
        result.put("indexCode", entity.code());
        result.put("ruleName", entity.name());
        result.put("category", entity.category());
        result.put("hospitalId", hospitalId);
        result.put("effectiveLevel", "company");
        result.put("profileId", entity.variantCode());
        result.put("profileName", entity.variantLabel());
        result.put("executionStatus", executionStatus);
        result.put("executionBlockers", List.of());
        result.put("definition", definition);
        result.put("formula", formula);
        result.put("numeratorRule", numeratorRule);
        result.put("denominatorRule", denominatorRule);
        result.put("filterRule", "");
        result.put("excludeRule", "");
        result.put("implementationStatus", standardSql);
        result.put("standardSql", standardSql);
        result.put("sourceExtractSql", entity.sourceTableSql());
        result.put("departmentDetailSql", entity.deptStatSql());
        result.put("patientDetailSql", entity.patientDetailSql());
        result.put("sqlCapabilities", Map.of(
                "overview", Map.of("status", hasSql ? "executable" : "unavailable")));
        result.put("extractionContract", buildExtractionContract(entity));
        result.put("dualDatabaseContract", Map.of());
        result.put("resultMapping", Map.of(
                "index_value", "监测情况",
                "numerator_count", "分子",
                "denominator_count", "分母"));
        result.put("resultContract", Map.of("unit", unit));
        result.put("overviewRuntimeEligible", hasSql);
        result.put("calculationDefinition", Map.of(
                "numerator", numeratorRule,
                "denominator", denominatorRule));
        result.put("nationalCalculationDefinition", Map.of(
                "numerator", numeratorRule,
                "denominator", denominatorRule));
        result.put("fieldContract", Map.of());
        result.put("fieldStatus", "mras_default");
        result.put("sqlStatus", hasSql ? "available" : "unavailable");
        result.put("hospitalOverride", null);
        result.put("companyRule", Map.of(
                "path", "knowledge-index/entities/" + entity.variantCode(),
                "implementation", entity.variantLabel(),
                "implementationStatus", executionStatus));
        result.put("nationalRule", Map.of(
                "definition", definition,
                "formula", formula,
                "version", "2025",
                "sourcePath", "knowledge-index/concepts/" + entity.code() + entity.name() + ".md"));
        result.put("nationalParams", Map.of());
        result.put("effectiveParams", Map.of(
                "marptBeginAt", "统计开始时间",
                "marptEndAt", "统计结束时间"));
        result.put("resultUnit", unit);
        result.put("nationalVersion", "2025");
        result.put("hospitalVersion", null);
        result.put("overriddenFields", List.of());
        result.put("fallbackChain", List.of("company"));
        result.put("ruleSource", "mras");
        result.put("knowledgeReleaseId", "mras-v60");
        result.put("warnings", List.of());
        result.put("relations", Map.of());
        result.put("significance", significance);
        result.put("system", entity.system());
        result.put("caliber", entity.caliber());
        result.put("dataSource", entity.dataSource());
        result.put("monitorParams", entity.monitorParams());
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
        result.put("ruleId", ruleId);
        result.put("profileId", entity != null ? entity.variantCode() : ruleId);
        result.put("executionStatus", entity != null && entity.hasOverviewSql() ? "executable" : "documentation_only");
        result.put("executionBlockers", List.of());
        result.put("hospitalId", hospitalId);
        result.put("status", "mras_default");
        result.put("dialect", "sqlserver");
        result.put("dbName", "WINDBA_GN");
        result.put("fields", Map.of());
        result.put("parameters", Map.of(
                "marptBeginAt", "统计开始时间",
                "marptEndAt", "统计结束时间"));
        result.put("relations", List.of());
        result.put("queryProfile", "");
        result.put("items", List.of());
        result.put("metadataItems", List.of());
        result.put("ruleSource", "mras");
        result.put("knowledgeReleaseId", "mras-v60");
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
                    profile.put("profileId", entity.variantCode());
                    profile.put("profileName", entity.variantLabel());
                    profile.put("label", entity.variantLabel());
                    profile.put("status", "approved");
                    profile.put("governanceStatus", "approved");
                    profile.put("executionStatus", "executable");
                    profile.put("overviewRuntimeEligible", true);
                    profile.put("parameterOverrides", Map.of());
                    profile.put("fieldRoleOverrides", Map.of());
                    profile.put("numeratorRule", extractPattern(entity.formula(), NUMERATOR_PATTERN));
                    profile.put("denominatorRule", extractPattern(entity.formula(), DENOMINATOR_PATTERN));
                    profile.put("timeDimension", entity.dimension());
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
        card.put("ruleId", entity.code());
        card.put("ruleName", entity.name());
        card.put("category", entity.category());
        card.put("content", entity.definition());
        card.put("formula", entity.formula());
        card.put("numerator", extractPattern(entity.formula(), NUMERATOR_PATTERN));
        card.put("denominator", extractPattern(entity.formula(), DENOMINATOR_PATTERN));
        card.put("timeDimension", entity.dimension());
        card.put("executionStatus", entity.hasOverviewSql() ? "executable" : "documentation_only");
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
                .replaceAll("[\\s\u3000\uff0c\u3002\uff1f\uff01\u3001\uff1a\uff1b\uff08\uff09()\u300a\u300b\"'`]+", "")
                .replace("\u7684", "");
    }

    private static Map<String, Object> buildExtractionContract(EntityPageData entity) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("event_table", entity.targetTable() == null ? "" : entity.targetTable());
        contract.put("dependency_tables", entity.bizTables() == null ? List.of() : entity.bizTables());
        if (entity.extendedEvents() != null && !entity.extendedEvents().isEmpty()) {
            List<Map<String, String>> extEvents = entity.extendedEvents().stream()
                    .map(e -> Map.of("eventNo", e.getKey(), "sqlScript", e.getValue()))
                    .toList();
            contract.put("extended_events", extEvents);
        }
        return contract;
    }
}

package com.hospital.wikiagent.rules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 直接读取 core-rules-wiki 的规则、医院口径、字段映射和 SQL 规格。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class WikiRuleKnowledgeSource {
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*分钟");
    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*小时");

    private final Path root;
    private final ObjectMapper objectMapper;
    private final Yaml yaml = new Yaml();

    public WikiRuleKnowledgeSource(
            @Value("${wiki.knowledge.root:core-rules-wiki}") String root,
            ObjectMapper objectMapper) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> searchForHospital(String query, String hospitalId, int limit) {
        String normalized = normalize(query);
        List<Map<String, Object>> matches = rules().stream()
                .filter(rule -> "active".equalsIgnoreCase(text(rule.get("status"))))
                .map(rule -> Map.entry(score(normalized, rule), rule))
                .filter(entry -> entry.getKey() > 0)
                .sorted(Comparator.<Map.Entry<Integer, Map<String, Object>>>comparingInt(Map.Entry::getKey)
                        .reversed()
                        .thenComparing(entry -> text(entry.getValue().get("rule_id"))))
                .limit(Math.max(1, limit))
                .map(entry -> match(entry.getValue()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query == null ? "" : query.strip());
        result.put("hospital_id", hospitalId);
        result.put("resolved_rule_id", matches.isEmpty() ? null : matches.get(0).get("rule_id"));
        result.put("matches", matches);
        result.put("rule_source", "wiki");
        return result;
    }

    public List<Map<String, String>> activeIndicatorNames(String hospitalId, int limit) {
        return rules().stream()
                .filter(rule -> "active".equalsIgnoreCase(text(rule.get("status"))))
                .sorted(Comparator.comparing(rule -> text(rule.get("rule_id"))))
                .limit(Math.max(1, Math.min(500, limit)))
                .map(rule -> Map.of(
                        "rule_id", text(rule.get("rule_id")),
                        "rule_name", text(rule.get("rule_name"))))
                .toList();
    }

    public Map<String, Object> effectiveRule(String query, String hospitalId) {
        Map<String, Object> rule = resolveRule(query);
        if (rule == null) {
            throw new RuleNotFoundException("RULE_NOT_FOUND: " + query);
        }
        String ruleId = text(rule.get("rule_id"));
        String nationalMarkdown = read(text(rule.get("national_path")));
        String companyMarkdown = read(text(rule.get("company_path")));
        Map<String, Object> overrideItem = hospitalOverride(ruleId, hospitalId);
        String overrideMarkdown = overrideItem == null ? "" : read(text(overrideItem.get("path")));
        Map<String, Object> spec = yaml(findSpecFile(ruleId, "rule_sql_spec.yaml"));
        Map<String, Object> contract = yaml(findSpecFile(ruleId, "field_contract.yaml"));
        Map<String, Object> mapping = hospitalMapping(ruleId, hospitalId);

        String nationalDefinition = section(nationalMarkdown, "指标定义");
        String nationalFormula = section(nationalMarkdown, "计算公式");
        String definition = overrideMarkdown.isBlank()
                ? nationalDefinition : first(section(overrideMarkdown, "本院指标定义"), nationalDefinition);
        String formula = overrideMarkdown.isBlank()
                ? nationalFormula : first(section(overrideMarkdown, "本院计算公式"), nationalFormula);
        String dialect = first(text(mapping.get("dialect")), "mysql");
        String sql = template(ruleId, dialect);
        Map<String, Object> effectiveParams = map(spec.get("default_params"));
        effectiveParams.putAll(map(mapping.get("filters")));
        effectiveParams.putAll(map(mapping.get("parameters")));
        applyTextThresholds(effectiveParams, formula + " " + definition);

        Map<String, Object> numerator = map(spec.get("numerator"));
        Map<String, Object> denominator = map(spec.get("denominator"));
        Map<String, Object> nationalRule = new LinkedHashMap<>();
        nationalRule.put("definition", nationalDefinition);
        nationalRule.put("formula", nationalFormula);
        nationalRule.put("version", frontMatter(nationalMarkdown).get("version"));
        nationalRule.put("source_path", rule.get("national_path"));

        Map<String, Object> hospitalOverride = overrideItem == null ? null : new LinkedHashMap<>(overrideItem);
        if (hospitalOverride != null) {
            hospitalOverride.put("definition", definition);
            hospitalOverride.put("formula", formula);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", ruleId);
        result.put("index_code", ruleId);
        result.put("rule_name", text(rule.get("rule_name")));
        result.put("category", text(rule.get("category")));
        result.put("hospital_id", hospitalId);
        result.put("effective_level", overrideItem == null ? "company" : "hospital");
        result.put("definition", definition);
        result.put("formula", formula);
        result.put("numerator_rule", text(numerator.get("name")));
        result.put("denominator_rule", text(denominator.get("name")));
        result.put("filter_rule", joinLogic(denominator.get("logic"), false));
        result.put("exclude_rule", joinLogic(numerator.get("logic"), true));
        result.put("implementation_status", sql);
        result.put("standard_sql", sql);
        result.put("calculation_definition", map(spec.get("calculation")));
        result.put("national_calculation_definition", map(spec.get("calculation")));
        result.put("field_contract", contract);
        result.put("field_status", text(mapping.get("status")));
        result.put("sql_status", !sql.isBlank() && "confirmed".equals(text(mapping.get("status")))
                ? "available" : "unavailable");
        result.put("hospital_override", hospitalOverride);
        result.put("company_rule", Map.of(
                "path", text(rule.get("company_path")),
                "implementation", section(companyMarkdown, "公司实现口径"),
                "implementation_status", section(companyMarkdown, "公司标准 SQL")));
        result.put("national_rule", nationalRule);
        result.put("national_params", map(spec.get("default_params")));
        result.put("effective_params", effectiveParams);
        result.put("national_version", nationalRule.get("version"));
        result.put("hospital_version", overrideItem == null ? null
                : overrideItem.getOrDefault("hospital_version", overrideItem.get("version")));
        result.put("overridden_fields", overrideItem == null ? List.of() : List.of("definition", "formula", "parameters"));
        result.put("fallback_chain", List.of("hospital", "company", "national"));
        result.put("rule_source", "wiki");
        result.put("warnings", overrideItem == null && hospitalId != null
                ? List.of("hospital_override_not_configured") : List.of());
        result.put("relations", relation(ruleId));
        return result;
    }

    public Map<String, Object> fieldMapping(String ruleId, String hospitalId) {
        Map<String, Object> mapping = hospitalMapping(ruleId, hospitalId);
        if (mapping.isEmpty()) {
            return Map.of(
                    "rule_id", ruleId,
                    "hospital_id", hospitalId,
                    "status", "missing",
                    "fields", Map.of(),
                    "items", List.of(),
                    "relations", List.of(),
                    "metadata_items", List.of(),
                    "rule_source", "wiki");
        }
        Map<String, Object> contract = yaml(findSpecFile(ruleId, "field_contract.yaml"));
        Map<String, Object> businessFields = map(contract.get("business_fields"));
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> metadataItems = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map(mapping.get("fields")).entrySet()) {
            String physical = text(entry.getValue());
            int split = physical.lastIndexOf('.');
            String table = split < 0 ? "" : physical.substring(0, split);
            String column = split < 0 ? physical : physical.substring(split + 1);
            String expected = text(map(businessFields.get(entry.getKey())).get("type"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("business_field", entry.getKey());
            item.put("db_name", mapping.get("db_name"));
            item.put("table_name", table);
            item.put("column_name", column);
            item.put("data_type", expected);
            item.put("status", mapping.get("status"));
            items.add(item);
            Map<String, Object> metadata = new LinkedHashMap<>(item);
            metadata.put("mapping_data_type", expected);
            metadata.put("metadata_data_type", expected);
            metadataItems.add(metadata);
        }
        Map<String, Object> result = new LinkedHashMap<>(mapping);
        result.put("rule_id", ruleId);
        result.put("hospital_id", hospitalId);
        result.put("items", items);
        result.put("metadata_items", metadataItems);
        result.put("relations", listOfMaps(mapping.get("relations")));
        result.put("rule_source", "wiki");
        return result;
    }

    /**
     * 返回同一指标可用于反事实诊断的已治理口径配置。
     *
     * <p>候选只允许来自指标 SQL 规格目录内的 YAML，不接受模型或用户提供 SQL。
     * 医院范围在知识源层先过滤，生效时间和最大执行数量由诊断 Workflow 再约束。</p>
     */
    public List<Map<String, Object>> diagnosticProfiles(String ruleId, String hospitalId) {
        Path path = findSpecFile(ruleId, "rule_sql_spec.yaml").getParent()
                .resolve("diagnosis_profiles.yaml");
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        return listOfMaps(yaml(path).get("profiles")).stream()
                .filter(profile -> "approved".equalsIgnoreCase(text(profile.get("status"))))
                .filter(profile -> {
                    List<String> hospitals = stringList(profile.get("hospital_ids"));
                    return hospitals.isEmpty() || hospitals.contains("*")
                            || (hospitalId != null && hospitals.contains(hospitalId));
                })
                .map(profile -> Collections.unmodifiableMap(new LinkedHashMap<>(profile)))
                .toList();
    }

    /**
     * 读取允许列表式数据质量规则。规则只能声明检查类型和业务字段，不能携带 SQL。
     */
    public List<Map<String, Object>> dataQualityRules(String ruleId) {
        Map<String, Object> spec = yaml(findSpecFile(ruleId, "rule_sql_spec.yaml"));
        return listOfMaps(spec.get("quality_checks")).stream()
                .map(item -> Map.copyOf(new LinkedHashMap<>(item)))
                .toList();
    }

    private List<Map<String, Object>> rules() {
        return listOfMaps(map(json("indexes/rule_index.json")).get("rules"));
    }

    private Map<String, Object> resolveRule(String query) {
        String normalized = normalize(query);
        return rules().stream()
                .map(rule -> Map.entry(score(normalized, rule), rule))
                .filter(entry -> entry.getKey() > 0)
                .max(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static int score(String query, Map<String, Object> rule) {
        if (query.isBlank()) return 0;
        List<String> candidates = new ArrayList<>();
        candidates.add(text(rule.get("rule_id")));
        candidates.add(text(rule.get("rule_name")));
        if (rule.get("aliases") instanceof List<?> aliases) {
            aliases.forEach(value -> candidates.add(text(value)));
        }
        int result = 0;
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (query.equals(normalized)) result = Math.max(result, 100);
            else if (!normalized.isBlank() && (query.contains(normalized) || normalized.contains(query))) {
                result = Math.max(result, 70 + Math.min(20, normalized.length()));
            } else {
                Set<Integer> left = normalized.codePoints().boxed().collect(java.util.stream.Collectors.toSet());
                Set<Integer> right = query.codePoints().boxed().collect(java.util.stream.Collectors.toSet());
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

    private Map<String, Object> match(Map<String, Object> rule) {
        String path = text(rule.get("national_path"));
        return Map.of(
                "rule_id", text(rule.get("rule_id")),
                "rule_name", text(rule.get("rule_name")),
                "category", text(rule.get("category")),
                "content", section(read(path), "指标定义"),
                "type", "wiki_rule",
                "path", path);
    }

    private Map<String, Object> hospitalMapping(String ruleId, String hospitalId) {
        if (hospitalId == null || hospitalId.isBlank()) return Map.of();
        Path path = root.resolve("hospital-mappings").resolve(hospitalId).resolve(ruleId + ".yaml");
        return Files.isRegularFile(path) ? yaml(path) : Map.of();
    }

    private Map<String, Object> hospitalOverride(String ruleId, String hospitalId) {
        if (hospitalId == null || hospitalId.isBlank()) return null;
        for (Map<String, Object> item : listOfMaps(map(json("indexes/hospital_override_index.json"))
                .get("hospital_overrides"))) {
            if (ruleId.equals(text(item.get("rule_id")))
                    && hospitalId.equals(text(item.get("hospital_id")))
                    && "approved".equals(text(item.get("status")))) {
                return item;
            }
        }
        return null;
    }

    private Map<String, Object> relation(String ruleId) {
        return map(map(json("indexes/relation_index.json")).get(ruleId));
    }

    private Path findSpecFile(String ruleId, String fileName) {
        Path specs = root.resolve("sql-specs");
        try (var paths = Files.list(specs)) {
            Path directory = paths
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(ruleId + "_"))
                    .findFirst()
                    .orElseThrow(() -> new RuleNotFoundException("SQL_SPEC_NOT_FOUND: " + ruleId));
            return directory.resolve(fileName);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Wiki SQL 规格", exception);
        }
    }

    private String template(String ruleId, String dialect) {
        try {
            Path spec = findSpecFile(ruleId, "rule_sql_spec.yaml").getParent();
            Path template = spec.resolve("templates").resolve(dialect.toLowerCase(Locale.ROOT) + ".sql.j2");
            return Files.isRegularFile(template) ? Files.readString(template, StandardCharsets.UTF_8) : "";
        } catch (IOException | RuleNotFoundException exception) {
            return "";
        }
    }

    private Object json(String relative) {
        try {
            return objectMapper.readValue(root.resolve(relative).toFile(), Object.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法读取 Wiki 索引: " + relative, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path path) {
        if (!Files.isRegularFile(path)) return new LinkedHashMap<>();
        try (InputStream input = Files.newInputStream(path)) {
            Object value = yaml.load(input);
            return value instanceof Map<?, ?> map ? map(map) : new LinkedHashMap<>();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Wiki YAML: " + path, exception);
        }
    }

    private String read(String relative) {
        if (relative == null || relative.isBlank()) return "";
        try {
            Path path = root.resolve(relative).normalize();
            return path.startsWith(root) && Files.isRegularFile(path)
                    ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException exception) {
            return "";
        }
    }

    private static String section(String markdown, String heading) {
        if (markdown == null || markdown.isBlank()) return "";
        Pattern pattern = Pattern.compile(
                "(?ms)^##\\s+" + Pattern.quote(heading) + "\\s*$\\R(.*?)(?=^##\\s+|\\z)");
        Matcher matcher = pattern.matcher(markdown);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static Map<String, Object> frontMatter(String markdown) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (markdown == null || !markdown.startsWith("---")) return result;
        int end = markdown.indexOf("\n---", 3);
        if (end < 0) return result;
        for (String line : markdown.substring(3, end).split("\\R")) {
            int split = line.indexOf(':');
            if (split > 0 && !line.startsWith(" ")) {
                result.put(line.substring(0, split).strip(), line.substring(split + 1).strip().replace("\"", ""));
            }
        }
        return result;
    }

    private static void applyTextThresholds(Map<String, Object> parameters, String text) {
        Matcher minute = MINUTES.matcher(text == null ? "" : text);
        Matcher hour = HOURS.matcher(text == null ? "" : text);
        Integer minuteValue = minute.find() ? Integer.valueOf(minute.group(1)) : null;
        Integer hourValue = hour.find() ? Integer.valueOf(hour.group(1)) : null;
        for (String key : new ArrayList<>(parameters.keySet())) {
            if (minuteValue != null && key.toLowerCase(Locale.ROOT).contains("minute")) {
                parameters.put(key, minuteValue);
            } else if (hourValue != null && key.toLowerCase(Locale.ROOT).contains("hour")) {
                parameters.put(key, hourValue);
            }
        }
    }

    private static String joinLogic(Object value, boolean onlyExclusions) {
        if (!(value instanceof List<?> list)) return "";
        return list.stream().map(String::valueOf)
                .filter(item -> !onlyExclusions
                        || item.contains("!=") || item.toLowerCase(Locale.ROOT).contains("exclude"))
                .collect(java.util.stream.Collectors.joining("；"));
    }

    private static String normalize(String value) {
        return text(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s　，。？！、：；（）()《》\"'`]+", "")
                .replace("的", "");
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).map(String::strip)
                .filter(item -> !item.isBlank()).toList();
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) result.add(map(item));
        return result;
    }
}

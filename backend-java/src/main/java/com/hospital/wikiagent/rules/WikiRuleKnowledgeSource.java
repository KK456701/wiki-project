package com.hospital.wikiagent.rules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 读取HXZD Wiki的机器契约，为规则搜索、口径解释和受控SQL执行提供唯一知识源。
 *
 * <p>原始Markdown只供人员阅读和生成器解析。运行时只消费生成器产出的
 * {@code rule_index.json} 与每项指标的 {@code runtime.json}，避免Java在请求期间
 * 猜测Markdown结构。Profile未标记为 {@code executable} 时只允许解释，不返回SQL正文。</p>
 */
@Component
public class WikiRuleKnowledgeSource {
    private static final long POINTER_CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private final Path configuredRoot;
    private final ObjectMapper objectMapper;
    private final Yaml yaml = new Yaml();
    private final AtomicReference<KnowledgeSnapshot> snapshot;
    private final AtomicLong lastPointerCheck = new AtomicLong();
    private volatile long pointerModifiedAt = Long.MIN_VALUE;

    public WikiRuleKnowledgeSource(
            @Value("${wiki.knowledge.root:core-rules-wiki}") String root,
            ObjectMapper objectMapper) {
        this.configuredRoot = resolveConfiguredRoot(root);
        this.objectMapper = objectMapper;
        this.snapshot = new AtomicReference<>(loadInitialSnapshot());
    }

    /**
     * 同一份配置既要支持从仓库根目录启动，也要支持开发人员在 {@code backend-java}
     * 子目录直接执行 Maven。仅当配置使用默认相对路径且当前目录不存在时，才回退到
     * 相邻目录；显式绝对路径或实际存在的相对路径绝不会被改写。
     */
    private static Path resolveConfiguredRoot(String root) {
        Path requested = Path.of(root).toAbsolutePath().normalize();
        if (Files.isDirectory(requested) || Path.of(root).isAbsolute()) {
            return requested;
        }
        Path sibling = Path.of("..").resolve(root).toAbsolutePath().normalize();
        return Files.isDirectory(sibling) ? sibling : requested;
    }

    public Map<String, Object> searchForHospital(String query, String hospitalId, int limit) {
        String normalized = normalize(query);
        Map<String, Integer> ngramHits = ngramHits(normalized);
        List<Map<String, Object>> matches = searchableRules().stream()
                .filter(rule -> "active".equalsIgnoreCase(text(rule.get("status"))))
                .map(rule -> Map.entry(rankScore(normalized, rule, ngramHits), rule))
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
        result.put("knowledge_release_id", currentSnapshot().releaseId());
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
        return effectiveRule(query, hospitalId, null);
    }

    /**
     * 读取指定Profile的规则快照。普通请求传空Profile并使用默认口径；候选口径计算必须
     * 显式传Profile编号，不能仅靠参数覆盖把一个方案伪装成另一个方案。
     */
    public Map<String, Object> effectiveRule(String query, String hospitalId, String profileId) {
        Map<String, Object> rule = resolveRule(query);
        if (rule == null) {
            throw new RuleNotFoundException("RULE_NOT_FOUND: " + query);
        }
        String ruleId = text(rule.get("rule_id"));
        ResolvedManifest resolved = resolvedManifest(rule, hospitalId);
        Map<String, Object> manifest = resolved.document();
        Map<String, Object> profile = profile(manifest, profileId);
        String selectedProfileId = text(profile.get("profile_id"));
        String executionStatus = first(text(profile.get("execution_status")), "documentation_only");
        List<String> blockers = stringList(profile.get("execution_blockers"));
        Map<String, Object> sqlRefs = map(profile.get("sql_refs"));
        Map<String, Object> sqlCapabilities = map(profile.get("sql_capabilities"));
        String overviewSql = executableSql(
                resolved.root(), executionStatus, sqlRefs, sqlCapabilities, "overview", "overview");
        String sourceExtractSql = executableSql(
                resolved.root(), executionStatus, sqlRefs, sqlCapabilities, "source_extract", "etl_source");
        String departmentDetailSql = executableSql(
                resolved.root(), executionStatus, sqlRefs, sqlCapabilities, "department_detail", "department");
        String patientDetailSql = executableSql(
                resolved.root(), executionStatus, sqlRefs, sqlCapabilities, "patient_detail", "patient_detail");
        Map<String, Object> mapping = mergedFieldMapping(profile, ruleId, hospitalId);
        Map<String, Object> params = map(mapping.get("parameters"));
        String definition = first(text(manifest.get("definition")),
                section(readFrom(resolved.root(), text(rule.get("national_path"))), "指标定义"));
        String formula = first(text(manifest.get("formula")),
                section(readFrom(resolved.root(), text(rule.get("national_path"))), "计算公式"));

        Map<String, Object> nationalRule = new LinkedHashMap<>();
        nationalRule.put("definition", definition);
        nationalRule.put("formula", formula);
        nationalRule.put("version", "2025");
        nationalRule.put("source_path", rule.get("source_path"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", ruleId);
        result.put("index_code", ruleId);
        result.put("rule_name", text(rule.get("rule_name")));
        result.put("category", first(text(rule.get("category")), text(manifest.get("category"))));
        result.put("hospital_id", hospitalId);
        result.put("effective_level", "company");
        result.put("profile_id", selectedProfileId);
        result.put("profile_name", profile.get("profile_name"));
        result.put("execution_status", executionStatus);
        result.put("execution_blockers", blockers);
        result.put("definition", definition);
        result.put("formula", formula);
        result.put("numerator_rule", text(profile.get("numerator_rule")));
        result.put("denominator_rule", text(profile.get("denominator_rule")));
        result.put("filter_rule", text(profile.get("denominator_caliber")));
        result.put("exclude_rule", text(profile.get("numerator_caliber")));
        result.put("implementation_status", overviewSql);
        result.put("standard_sql", overviewSql);
        result.put("source_extract_sql", sourceExtractSql);
        result.put("department_detail_sql", departmentDetailSql);
        result.put("patient_detail_sql", patientDetailSql);
        result.put("sql_capabilities", sqlCapabilities);
        result.put("dual_database_contract", map(profile.get("dual_database_contract")));
        result.put("result_mapping", map(profile.get("result_mapping")));
        result.put("calculation_definition", calculation(profile));
        result.put("national_calculation_definition", calculation(profile));
        result.put("field_contract", map(profile.get("field_contract")));
        result.put("field_status", text(mapping.get("status")));
        result.put("sql_status", "executable".equals(executionStatus) && !overviewSql.isBlank()
                ? "available" : "unavailable");
        result.put("hospital_override", null);
        result.put("company_rule", Map.of(
                "path", text(rule.get("company_path")),
                "implementation", text(profile.get("profile_name")),
                "implementation_status", executionStatus));
        result.put("national_rule", nationalRule);
        result.put("national_params", Map.of());
        result.put("effective_params", params);
        result.put("result_unit", "percentage");
        result.put("national_version", "2025");
        result.put("hospital_version", null);
        result.put("overridden_fields", List.of());
        result.put("fallback_chain", List.of("company", "national"));
        result.put("rule_source", "wiki");
        result.put("knowledge_release_id", resolved.releaseId());
        result.put("warnings", blockers);
        result.put("relations", relation(ruleId));
        return result;
    }

    private String executableSql(
            Path root,
            String profileStatus,
            Map<String, Object> refs,
            Map<String, Object> capabilities,
            String capability,
            String referenceKey) {
        if (!"executable".equals(profileStatus)) {
            return "";
        }
        Map<String, Object> contract = map(capabilities.get(capability));
        if (!"executable".equals(text(contract.get("status")))) {
            return "";
        }
        return readFrom(root, text(refs.get(referenceKey)));
    }

    public Map<String, Object> fieldMapping(String ruleId, String hospitalId) {
        return fieldMapping(ruleId, hospitalId, null);
    }

    public Map<String, Object> fieldMapping(String ruleId, String hospitalId, String profileId) {
        Map<String, Object> rule = findRule(ruleId);
        ResolvedManifest resolved = resolvedManifest(rule, hospitalId);
        Map<String, Object> selected = profile(resolved.document(), profileId);
        Map<String, Object> mapping = mergedFieldMapping(selected, ruleId, hospitalId);
        Map<String, Object> businessFields = map(map(selected.get("field_contract")).get("business_fields"));
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
        result.put("profile_id", selected.get("profile_id"));
        result.put("execution_status", selected.get("execution_status"));
        result.put("execution_blockers", selected.get("execution_blockers"));
        result.put("hospital_id", hospitalId);
        result.put("items", items);
        result.put("metadata_items", metadataItems);
        result.put("relations", listOfMaps(mapping.get("relations")));
        result.put("rule_source", "wiki");
        result.put("knowledge_release_id", resolved.releaseId());
        return result;
    }

    /**
     * 只返回真正可以执行的候选口径。仅文档Profile可以参与解释，但不能进入反事实试算。
     */
    public List<Map<String, Object>> caliberProfiles(String ruleId, String hospitalId) {
        Map<String, Object> rule = findRule(ruleId);
        return listOfMaps(resolvedManifest(rule, hospitalId).document().get("profiles")).stream()
                .filter(profile -> "approved".equalsIgnoreCase(text(profile.get("governance_status"))))
                .filter(profile -> "executable".equalsIgnoreCase(text(profile.get("execution_status"))))
                .filter(profile -> visibleToHospital(profile, hospitalId))
                .map(profile -> {
                    Map<String, Object> value = new LinkedHashMap<>(profile);
                    value.put("status", "approved");
                    value.put("parameter_overrides", map(profile.get("parameter_overrides")));
                    value.put("field_role_overrides", map(profile.get("field_role_overrides")));
                    // Profile中允许保留空的生效结束时间等可选字段，
                    // Map.copyOf 会因 null 值抛出异常，因此这里返回当前方法
                    // 已创建的独立可变副本；调用方无法借此修改缓存中的契约。
                    return value;
                })
                .toList();
    }

    public List<Map<String, Object>> diagnosticProfiles(String ruleId, String hospitalId) {
        return caliberProfiles(ruleId, hospitalId);
    }

    public List<Map<String, Object>> dataQualityRules(String ruleId) {
        Map<String, Object> rule = findRule(ruleId);
        return listOfMaps(manifest(rule).get("quality_checks")).stream()
                .map(item -> Map.copyOf(new LinkedHashMap<>(item)))
                .toList();
    }

    private Map<String, Object> calculation(Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("numerator", profile.get("numerator_rule"));
        result.put("denominator", profile.get("denominator_rule"));
        result.put("numerator_caliber", profile.get("numerator_caliber"));
        result.put("denominator_caliber", profile.get("denominator_caliber"));
        result.put("time_dimension", profile.get("time_dimension"));
        result.put("dedup_key", profile.get("dedup_key"));
        return result;
    }

    private Map<String, Object> mergedFieldMapping(
            Map<String, Object> profile, String ruleId, String hospitalId) {
        Map<String, Object> result = map(profile.get("field_mapping"));
        Map<String, Object> hospital = hospitalMapping(ruleId, hospitalId);
        if (!hospital.isEmpty()) {
            result.putAll(hospital);
        }
        result.putIfAbsent("status", "missing");
        result.putIfAbsent("dialect", "sqlserver");
        result.putIfAbsent("fields", Map.of());
        result.putIfAbsent("parameters", Map.of());
        result.putIfAbsent("relations", List.of());
        result.putIfAbsent("query_profile", "");
        return result;
    }

    private Map<String, Object> manifest(Map<String, Object> rule) {
        return resolvedManifest(rule, null).document();
    }

    /**
     * 医院发布版本只覆盖与当前公司基础版本一致的完整快照。这样医院已经验证的
     * Profile 可以成为该医院的运行契约，同时公司发版后不会静默套用旧医院配置。
     */
    private ResolvedManifest resolvedManifest(Map<String, Object> rule, String hospitalId) {
        String path = first(
                text(rule.get("runtime_path")),
                "sql-specs/" + text(rule.get("rule_id")) + "/runtime.json");
        Path hospitalRoot = hospitalReleaseRoot(hospitalId);
        if (hospitalRoot != null) {
            Path hospitalManifest = hospitalRoot.resolve(path).normalize();
            if (hospitalManifest.startsWith(hospitalRoot)
                    && Files.isRegularFile(hospitalManifest)) {
                Map<String, Object> release = directJson(
                        hospitalRoot.resolve("release-manifest.json"));
                return new ResolvedManifest(
                        hospitalRoot,
                        text(release.get("release_id")),
                        directJson(hospitalManifest));
            }
        }
        KnowledgeSnapshot company = currentSnapshot();
        return new ResolvedManifest(
                company.root(), company.releaseId(), map(json(path)));
    }

    private Map<String, Object> profile(Map<String, Object> manifest, String requestedProfileId) {
        List<Map<String, Object>> profiles = listOfMaps(manifest.get("profiles"));
        String explicitProfileId = text(requestedProfileId);
        if (!explicitProfileId.isBlank()) {
            Map<String, Object> selected = profiles.stream()
                    .filter(item -> explicitProfileId.equals(text(item.get("profile_id"))))
                    .findFirst()
                    .orElseThrow(() -> new RuleNotFoundException("PROFILE_NOT_FOUND: " + explicitProfileId));
            if ("draft".equalsIgnoreCase(text(selected.get("execution_status")))) {
                throw new RuleNotFoundException("PROFILE_DRAFT_NOT_EFFECTIVE: " + explicitProfileId);
            }
            return selected;
        }

        String defaultProfileId = text(manifest.get("default_profile"));
        Map<String, Object> selected = profiles.stream()
                .filter(item -> defaultProfileId.equals(text(item.get("profile_id"))))
                .filter(item -> !"draft".equalsIgnoreCase(text(item.get("execution_status"))))
                .findFirst()
                .orElseGet(() -> profiles.stream()
                        .filter(item -> !"draft".equalsIgnoreCase(text(item.get("execution_status"))))
                        .findFirst()
                        .orElse(null));
        if (selected != null) {
            return selected;
        }
        return documentationFallback(profiles);
    }

    /**
     * 只有草稿方案时仍允许用户查阅指标文档，但不暴露草稿Profile身份，也不携带任何
     * SQL或字段映射。分子、分母文字来自原始制度文档，仅用于解释，不代表方案已生效。
     */
    private Map<String, Object> documentationFallback(List<Map<String, Object>> profiles) {
        Map<String, Object> source = profiles.isEmpty() ? Map.of() : profiles.get(0);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("profile_id", "");
        fallback.put("profile_name", "指标文档（暂无已审批生效口径）");
        fallback.put("governance_status", "documentation_only");
        fallback.put("execution_status", "documentation_only");
        fallback.put("execution_blockers", List.of("当前指标没有可进入生效口径的已审批Profile"));
        fallback.put("numerator_rule", text(source.get("numerator_rule")));
        fallback.put("numerator_caliber", text(source.get("numerator_caliber")));
        fallback.put("denominator_rule", text(source.get("denominator_rule")));
        fallback.put("denominator_caliber", text(source.get("denominator_caliber")));
        fallback.put("time_dimension", text(source.get("time_dimension")));
        fallback.put("dedup_key", text(source.get("dedup_key")));
        fallback.put("sql_refs", Map.of());
        fallback.put("result_mapping", Map.of());
        fallback.put("field_contract", Map.of("business_fields", Map.of()));
        fallback.put("field_mapping", Map.of(
                "status", "missing",
                "dialect", "sqlserver",
                "fields", Map.of(),
                "parameters", Map.of(),
                "relations", List.of()));
        return fallback;
    }

    private List<Map<String, Object>> rules() {
        return listOfMaps(map(json("indexes/rule_index.json")).get("rules"));
    }

    /**
     * v2优先使用预生成的紧凑检索卡；旧知识库没有检索卡时自动退回规则索引。
     * 卡片只含模型消歧所需摘要，不包含整份Markdown或SQL正文。
     */
    private List<Map<String, Object>> searchableRules() {
        List<Map<String, Object>> rules = rules();
        Map<String, Map<String, Object>> cards = new LinkedHashMap<>();
        try {
            for (Map<String, Object> card
                    : listOfMaps(map(json("indexes/retrieval_cards.json")).get("cards"))) {
                cards.put(text(card.get("rule_id")), card);
            }
        } catch (IllegalStateException ignored) {
            return rules;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Map<String, Object> merged = new LinkedHashMap<>(rule);
            Map<String, Object> card = cards.get(text(rule.get("rule_id")));
            if (card != null) merged.putAll(card);
            result.add(merged);
        }
        return result;
    }

    private Map<String, Object> findRule(String ruleId) {
        return rules().stream()
                .filter(rule -> ruleId.equals(text(rule.get("rule_id"))))
                .findFirst()
                .orElseThrow(() -> new RuleNotFoundException("RULE_NOT_FOUND: " + ruleId));
    }

    private Map<String, Object> resolveRule(String query) {
        String normalized = normalize(query);
        Map<String, Integer> ngramHits = ngramHits(normalized);
        return searchableRules().stream()
                .map(rule -> Map.entry(rankScore(normalized, rule, ngramHits), rule))
                .filter(entry -> entry.getKey() > 0)
                .max(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * 使用发布时生成的二元字符倒排索引给本地相似度排序加权。倒排索引只做候选排序，
     * 不会让一个没有任何词面相关性的指标凭空命中，因此旧版本缺少索引时可安全降级。
     */
    private Map<String, Integer> ngramHits(String query) {
        if (query.length() < 2) return Map.of();
        try {
            Map<String, Object> entries = map(map(json("indexes/ngram_index.json")).get("entries"));
            Map<String, Integer> result = new LinkedHashMap<>();
            for (int index = 0; index < query.length() - 1; index++) {
                String gram = query.substring(index, index + 2);
                for (String ruleId : stringList(entries.get(gram))) {
                    result.merge(ruleId, 1, Integer::sum);
                }
            }
            return result;
        } catch (IllegalStateException exception) {
            return Map.of();
        }
    }

    private static int rankScore(
            String query, Map<String, Object> rule, Map<String, Integer> ngramHits) {
        int lexical = score(query, rule);
        if (lexical == 0) return 0;
        return lexical + Math.min(20, ngramHits.getOrDefault(text(rule.get("rule_id")), 0) * 2);
    }

    private static int score(String query, Map<String, Object> rule) {
        if (query.isBlank()) return 0;
        List<String> candidates = new ArrayList<>();
        candidates.add(text(rule.get("rule_id")));
        candidates.add(text(rule.get("rule_name")));
        candidates.add(text(rule.get("category")));
        candidates.add(text(rule.get("system_name")));
        stringList(rule.get("aliases")).forEach(candidates::add);
        stringList(rule.get("keywords")).forEach(candidates::add);
        int result = 0;
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (query.equals(normalized)) {
                result = Math.max(result, 100);
            } else if (!normalized.isBlank() && (query.contains(normalized) || normalized.contains(query))) {
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule_id", text(rule.get("rule_id")));
        result.put("rule_name", text(rule.get("rule_name")));
        result.put("category", text(rule.get("category")));
        result.put("content", first(text(rule.get("definition_short")),
                section(read(path), "指标定义")));
        result.put("formula", text(rule.get("formula_short")));
        result.put("numerator", text(rule.get("numerator_short")));
        result.put("denominator", text(rule.get("denominator_short")));
        result.put("time_dimension", text(rule.get("time_dimension")));
        result.put("execution_status", text(rule.get("execution_status")));
        result.put("type", "wiki_rule");
        result.put("path", path);
        return result;
    }

    private Map<String, Object> hospitalMapping(String ruleId, String hospitalId) {
        if (hospitalId == null || hospitalId.isBlank()) return Map.of();
        List<Path> candidates = new ArrayList<>();
        Path hospitalRelease = hospitalReleaseRoot(hospitalId);
        if (hospitalRelease != null) {
            candidates.add(hospitalRelease.resolve("hospital-mappings")
                    .resolve(hospitalId).resolve(ruleId + ".yaml"));
        }
        candidates.add(activeRoot().resolve("hospital-mappings")
                .resolve(hospitalId).resolve(ruleId + ".yaml"));
        candidates.add(configuredRoot.resolve("hospital-mappings")
                .resolve(hospitalId).resolve(ruleId + ".yaml"));
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .map(this::yaml)
                .orElseGet(Map::of);
    }

    private Map<String, Object> relation(String ruleId) {
        return map(map(json("indexes/relation_index.json")).get(ruleId));
    }

    private Object json(String relative) {
        KnowledgeSnapshot current = currentSnapshot();
        return current.documents().computeIfAbsent(relative, key -> readJson(current.root(), key));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path path) {
        if (!Files.isRegularFile(path)) return new LinkedHashMap<>();
        try (InputStream input = Files.newInputStream(path)) {
            Object value = yaml.load(input);
            return value instanceof Map<?, ?> source ? map(source) : new LinkedHashMap<>();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 Wiki YAML: " + path, exception);
        }
    }

    private String read(String relative) {
        return readFrom(activeRoot(), relative);
    }

    private String readFrom(Path root, String relative) {
        if (root == null || relative == null || relative.isBlank()) return "";
        try {
            Path path = root.resolve(relative).normalize();
            return path.startsWith(root) && Files.isRegularFile(path)
                    ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException exception) {
            return "";
        }
    }

    /**
     * 返回当前完整知识快照。指针检查有一秒节流，避免每次规则读取都访问磁盘；
     * 新版本必须先完整通过清单哈希校验，之后才一次性替换引用。
     */
    private KnowledgeSnapshot currentSnapshot() {
        long now = System.nanoTime();
        long previous = lastPointerCheck.get();
        if (now - previous < POINTER_CHECK_INTERVAL_NANOS
                || !lastPointerCheck.compareAndSet(previous, now)) {
            return snapshot.get();
        }
        Path pointer = configuredRoot.resolve("pointers").resolve("company-current.json");
        long modified = modifiedAt(pointer);
        if (modified == pointerModifiedAt) return snapshot.get();
        try {
            KnowledgeSnapshot loaded = loadCurrentSnapshot();
            snapshot.set(loaded);
            pointerModifiedAt = modified;
        } catch (RuntimeException ignored) {
            // 发布指针或快照损坏时继续使用最后一个已验证版本，不能让半成品影响在线问答。
        }
        return snapshot.get();
    }

    private Path activeRoot() {
        return currentSnapshot().root();
    }

    private KnowledgeSnapshot loadCurrentSnapshot() {
        Path pointer = configuredRoot.resolve("pointers").resolve("company-current.json");
        if (!Files.isRegularFile(pointer)) return legacySnapshot();
        Map<String, Object> value = directJson(pointer);
        Path root = configuredRoot.resolve(text(value.get("release_path"))).normalize();
        validateReleaseRoot(root);
        String releaseId = text(value.get("release_id"));
        Map<String, Object> manifest = directJson(root.resolve("release-manifest.json"));
        if (releaseId.isBlank() || !releaseId.equals(text(manifest.get("release_id")))) {
            throw new IllegalStateException("知识版本指针与发布清单编号不一致");
        }
        pointerModifiedAt = modifiedAt(pointer);
        if (!Files.isRegularFile(root.resolve("indexes").resolve("rule_index.json"))) {
            throw new IllegalStateException("知识库缺少规则索引: " + root);
        }
        return new KnowledgeSnapshot(root, releaseId, new ConcurrentHashMap<>());
    }

    private KnowledgeSnapshot loadInitialSnapshot() {
        try {
            return loadCurrentSnapshot();
        } catch (RuntimeException exception) {
            // 仅首次启动允许回退迁移期兼容目录。运行过程中若新指针损坏，
            // currentSnapshot 会保留内存中的最后一个已验证版本。
            return legacySnapshot();
        }
    }

    private KnowledgeSnapshot legacySnapshot() {
        if (!Files.isRegularFile(configuredRoot.resolve("indexes").resolve("rule_index.json"))) {
            throw new IllegalStateException("知识库缺少规则索引: " + configuredRoot);
        }
        return new KnowledgeSnapshot(configuredRoot, "legacy-current", new ConcurrentHashMap<>());
    }

    private Path hospitalReleaseRoot(String hospitalId) {
        Path pointer = configuredRoot.resolve("pointers").resolve("hospitals")
                .resolve(hospitalId + "-current.json");
        if (!Files.isRegularFile(pointer)) return null;
        try {
            Map<String, Object> value = directJson(pointer);
            if (!hospitalId.equals(text(value.get("hospital_id")))) return null;
            Path root = configuredRoot.resolve(text(value.get("release_path"))).normalize();
            validateReleaseRoot(root);
            Map<String, Object> manifest = directJson(root.resolve("release-manifest.json"));
            if (!hospitalId.equals(text(manifest.get("hospital_id")))
                    || !currentSnapshot().releaseId().equals(text(manifest.get("base_release_id")))) {
                return null;
            }
            return root;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void validateReleaseRoot(Path root) {
        if (!root.startsWith(configuredRoot) || !Files.isDirectory(root)) {
            throw new IllegalStateException("知识版本路径越界或不存在");
        }
        Path manifestPath = root.resolve("release-manifest.json");
        if (!Files.isRegularFile(manifestPath)) throw new IllegalStateException("知识版本缺少发布清单");
        Map<String, Object> manifest = directJson(manifestPath);
        if (!"knowledge-release-v2".equals(text(manifest.get("schema_version")))) {
            throw new IllegalStateException("知识版本清单格式不受支持");
        }
        if (text(manifest.get("release_id")).isBlank()) {
            throw new IllegalStateException("知识版本清单缺少release_id");
        }
        for (Map.Entry<String, Object> entry : map(manifest.get("files")).entrySet()) {
            Path file = root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)
                    || !text(entry.getValue()).equals(sha256(file))) {
                throw new IllegalStateException("知识版本文件校验失败: " + entry.getKey());
            }
        }
        Map<String, Object> rules = directJson(root.resolve("indexes").resolve("rule_index.json"));
        Map<String, Object> cards = directJson(root.resolve("indexes").resolve("retrieval_cards.json"));
        String releaseId = text(manifest.get("release_id"));
        if (!releaseId.equals(text(rules.get("release_id")))
                || !releaseId.equals(text(cards.get("release_id")))) {
            throw new IllegalStateException("知识版本索引与发布清单编号不一致");
        }
    }

    private Object readJson(Path root, String relative) {
        try {
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                throw new IOException("文件不存在或路径越界");
            }
            return objectMapper.readValue(path.toFile(), Object.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法读取 Wiki 机器契约: " + relative, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> directJson(Path path) {
        try {
            Object value = objectMapper.readValue(path.toFile(), Object.class);
            return value instanceof Map<?, ?> source ? map(source) : Map.of();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取知识版本文件: " + path, exception);
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("无法校验知识版本文件: " + path, exception);
        }
    }

    private record KnowledgeSnapshot(
            Path root,
            String releaseId,
            ConcurrentHashMap<String, Object> documents) {
    }

    private record ResolvedManifest(
            Path root,
            String releaseId,
            Map<String, Object> document) {
    }

    private static boolean visibleToHospital(Map<String, Object> profile, String hospitalId) {
        List<String> hospitals = stringList(profile.get("hospital_ids"));
        return hospitals.isEmpty() || hospitals.contains("*")
                || (hospitalId != null && hospitals.contains(hospitalId));
    }

    private static String section(String markdown, String heading) {
        if (markdown == null || markdown.isBlank()) return "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?ms)^##\\s+" + java.util.regex.Pattern.quote(heading)
                        + "\\s*$\\R(.*?)(?=^##\\s+|\\z)");
        java.util.regex.Matcher matcher = pattern.matcher(markdown);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static String normalize(String value) {
        return text(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s　，。？！、：；（）()《》\"'`]+", "")
                .replace("的", "");
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

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

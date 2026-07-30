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
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 读取HXZD Wiki的机器契约，为规则搜索、口径解释和受控SQL执行提供唯一知识源。
 *
 * <p>原始Markdown只供人员阅读和生成器解析。运行时只消费生成器产出的
 * {@code rule_index.json} 与每项指标的 {@code runtime.json}，避免Java在请求期间
 * 猜测Markdown结构。Profile未完成生产验收时可以返回通过静态门禁的 SQL 参考稿，
 * 但数据库访问仍由运行时执行门禁控制。</p>
 *
 * <p>已被 {@link MrasRuleKnowledgeSource}（领导知识库适配器）替代，
 * 不再作为 Spring Bean 注册。保留类文件供子类继承和旧测试引用。</p>
 */
public class WikiRuleKnowledgeSource {
    private static final long POINTER_CHECK_INTERVAL_NANOS = 1_000_000_000L;

    private final Path configuredRoot;
    private final ObjectMapper objectMapper;
    private final Yaml yaml = new Yaml();
    private final AtomicReference<KnowledgeSnapshot> snapshot;
    private final AtomicLong lastPointerCheck = new AtomicLong();
    private volatile long pointerModifiedAt = Long.MIN_VALUE;

    /**
     * 子类覆盖所有公共方法时使用的空壳构造函数，不加载任何知识库文件。
     */
    protected WikiRuleKnowledgeSource() {
        this.configuredRoot = Path.of(".");
        this.objectMapper = new ObjectMapper();
        this.snapshot = new AtomicReference<>(
                new KnowledgeSnapshot(Path.of("."), "unused", new ConcurrentHashMap<>()));
    }

    public WikiRuleKnowledgeSource(
            @Value("${wiki.knowledge.root:core-rules-wiki}") String root,
            ObjectMapper objectMapper) {
        this.configuredRoot = resolveConfiguredRoot(root);
        this.objectMapper = objectMapper;
        this.snapshot = new AtomicReference<>(loadInitialSnapshot());
    }

    /**
     * 同一份配置既要支持外置知识库，也要支持知识库随应用放在
     * {@code src/main/resources} 中。仅当相对路径不存在时才依次检查相邻目录、
     * 项目资源目录和 Maven 编译资源目录；显式绝对路径绝不会被改写。
     */
    private static Path resolveConfiguredRoot(String root) {
        Path raw = Path.of(root);
        Path requested = raw.toAbsolutePath().normalize();
        if (Files.isDirectory(requested) || raw.isAbsolute()) {
            return requested;
        }
        String directoryName = raw.getFileName() == null ? root : raw.getFileName().toString();
        List<Path> fallbacks = List.of(
                Path.of("..").resolve(root).toAbsolutePath().normalize(),
                Path.of("src", "main", "resources", directoryName).toAbsolutePath().normalize(),
                Path.of("target", "classes", directoryName).toAbsolutePath().normalize());
        return fallbacks.stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(requested);
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
        result.put("hospitalId", hospitalId);
        result.put("resolvedRuleId", matches.isEmpty() ? null : matches.get(0).get("ruleId"));
        result.put("matches", matches);
        result.put("ruleSource", "wiki");
        result.put("knowledgeReleaseId", currentSnapshot().releaseId());
        return result;
    }

    public List<Map<String, String>> activeIndicatorNames(String hospitalId, int limit) {
        return rules().stream()
                .filter(rule -> "active".equalsIgnoreCase(text(rule.get("status"))))
                .sorted(Comparator.comparing(rule -> text(rule.get("rule_id"))))
                .limit(Math.max(1, Math.min(500, limit)))
                .map(rule -> Map.of(
                        "ruleId", text(rule.get("rule_id")),
                        "ruleName", text(rule.get("rule_name"))))
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
        /*
         * SQL 是否存在、是否可供人工查看，与 Profile 是否已经完成生产执行验收是两件事。
         * 旧实现只给 executable Profile 读取 SQL，导致 documentation_only 指标明明有
         * overview.sql，用户询问“SQL 怎么写”时却得到“没有模板”。这里始终读取已经
         * 通过静态门禁的 SQL；真正是否允许访问数据库仍由 IndicatorSqlTools 和双库
         * Workflow 在运行时独立判断。
         */
        String overviewSql = validatedReferenceSql(
                resolved.root(), sqlRefs, sqlCapabilities, "overview", "overview");
        String sourceExtractSql = validatedReferenceSql(
                resolved.root(), sqlRefs, sqlCapabilities, "source_extract", "etl_source");
        String departmentDetailSql = validatedReferenceSql(
                resolved.root(), sqlRefs, sqlCapabilities, "department_detail", "department");
        String patientDetailSql = validatedReferenceSql(
                resolved.root(), sqlRefs, sqlCapabilities, "patient_detail", "patient_detail");
        Map<String, Object> resultMapping = resultMapping(profile);
        Map<String, Object> dualDatabaseContract = dualDatabaseContract(profile, resultMapping);
        Map<String, Object> resultContract = map(profile.get("result_contract"));
        boolean overviewRuntimeEligible = overviewRuntimeEligible(
                overviewSql, sqlCapabilities, resultMapping);
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
        nationalRule.put("sourcePath", rule.get("source_path"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", ruleId);
        result.put("indexCode", ruleId);
        result.put("ruleName", text(rule.get("rule_name")));
        result.put("category", first(text(rule.get("category")), text(manifest.get("category"))));
        result.put("hospitalId", hospitalId);
        result.put("effectiveLevel", "company");
        result.put("profileId", selectedProfileId);
        result.put("profileName", profile.get("profile_name"));
        result.put("executionStatus", executionStatus);
        result.put("executionBlockers", blockers);
        result.put("definition", definition);
        result.put("formula", formula);
        result.put("numeratorRule", text(profile.get("numerator_rule")));
        result.put("denominatorRule", text(profile.get("denominator_rule")));
        result.put("filterRule", first(
                text(profile.get("filter_rule")),
                text(profile.get("filter_caliber"))));
        result.put("excludeRule", first(
                text(profile.get("exclude_rule")),
                text(profile.get("exclusion_rule")),
                text(profile.get("exclusions"))));
        result.put("implementationStatus", overviewSql);
        result.put("standardSql", overviewSql);
        result.put("sourceExtractSql", sourceExtractSql);
        result.put("departmentDetailSql", departmentDetailSql);
        result.put("patientDetailSql", patientDetailSql);
        result.put("sqlCapabilities", sqlCapabilities);
        result.put("extractionContract", map(profile.get("extraction_contract")));
        result.put("dualDatabaseContract", dualDatabaseContract);
        result.put("resultMapping", resultMapping);
        result.put("resultContract", resultContract);
        result.put("overviewRuntimeEligible", overviewRuntimeEligible);
        result.put("calculationDefinition", calculation(profile));
        result.put("nationalCalculationDefinition", calculation(profile));
        result.put("fieldContract", map(profile.get("field_contract")));
        result.put("fieldStatus", text(mapping.get("status")));
        result.put("sqlStatus", "executable".equals(executionStatus) && !overviewSql.isBlank()
                ? "available"
                : overviewRuntimeEligible ? "overview_static_validated" : "unavailable");
        result.put("hospitalOverride", null);
        result.put("companyRule", Map.of(
                "path", text(rule.get("company_path")),
                "implementation", text(profile.get("profile_name")),
                "implementationStatus", executionStatus));
        result.put("nationalRule", nationalRule);
        result.put("nationalParams", Map.of());
        result.put("effectiveParams", params);
        result.put("resultUnit", first(
                text(resultContract.get("unit")),
                text(manifest.get("unit")),
                "percentage"));
        result.put("nationalVersion", "2025");
        result.put("hospitalVersion", null);
        result.put("overriddenFields", List.of());
        result.put("fallbackChain", List.of("company", "national"));
        result.put("ruleSource", "wiki");
        result.put("knowledgeReleaseId", resolved.releaseId());
        result.put("warnings", blockers);
        result.put("relations", relation(ruleId));
        return result;
    }

    private String validatedReferenceSql(
            Path root,
            Map<String, Object> refs,
            Map<String, Object> capabilities,
            String capability,
            String referenceKey) {
        Map<String, Object> contract = map(capabilities.get(capability));
        if (!Set.of(
                "static_validated",
                "metadata_validated",
                "compile_validated",
                "trial_validated",
                "executable").contains(text(contract.get("status")))) {
            return "";
        }
        return readFrom(root, text(refs.get(referenceKey)));
    }

    /**
     * 发布生成器会先给出结果列候选，完成真实库验收后再写入正式映射。概览只读试算
     * 可以安全使用这些确定性候选：运行结果若没有对应列，双库 Workflow 会立即以
     * 结果契约错误停止，不能生成业务结论。
     */
    private static Map<String, Object> resultMapping(Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>(map(profile.get("result_mapping")));
        Map<String, Object> candidates = map(profile.get("result_mapping_candidates"));
        for (String key : List.of("index_value", "numerator_count", "denominator_count")) {
            if (text(result.get(key)).isBlank() && !text(candidates.get(key)).isBlank()) {
                result.put(key, candidates.get(key));
            }
        }
        return result;
    }

    /** 将概览结果列映射补入双库契约，避免同一 Profile 在两个位置使用不同列名。 */
    private static Map<String, Object> dualDatabaseContract(
            Map<String, Object> profile,
            Map<String, Object> resultMapping) {
        Map<String, Object> result =
                new LinkedHashMap<>(map(profile.get("dual_database_contract")));
        result.put("result_contract", map(profile.get("result_contract")));
        Map<String, Object> overview =
                new LinkedHashMap<>(map(result.get("overview_result_mapping")));
        boolean explicitMapping = List.of(
                        "index_value", "numerator_count", "denominator_count")
                .stream()
                .anyMatch(key -> !text(overview.get(key)).isBlank());
        if (!explicitMapping) {
            for (String key : List.of(
                    "index_value", "numerator_count", "denominator_count")) {
                if (!text(resultMapping.get(key)).isBlank()) {
                    overview.put(key, resultMapping.get(key));
                }
            }
        }
        result.put("overview_result_mapping", overview);
        return result;
    }

    private static boolean overviewRuntimeEligible(
            String overviewSql,
            Map<String, Object> capabilities,
            Map<String, Object> resultMapping) {
        Map<String, Object> overview = map(capabilities.get("overview"));
        return !overviewSql.isBlank()
                && Set.of(
                        "static_validated",
                        "metadata_validated",
                        "compile_validated",
                        "trial_validated",
                        "executable").contains(text(overview.get("status")))
                && stringList(overview.get("blockers")).isEmpty()
                && stringList(overview.get("unknown_functions")).isEmpty()
                && ((!text(resultMapping.get("numerator_count")).isBlank()
                        && !text(resultMapping.get("denominator_count")).isBlank())
                    || !text(resultMapping.get("index_value")).isBlank());
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
            item.put("businessField", entry.getKey());
            item.put("dbName", mapping.get("dbName"));
            item.put("tableName", table);
            item.put("columnName", column);
            item.put("dataType", expected);
            item.put("status", mapping.get("status"));
            items.add(item);
            Map<String, Object> metadata = new LinkedHashMap<>(item);
            metadata.put("mappingDataType", expected);
            metadata.put("metadataDataType", expected);
            metadataItems.add(metadata);
        }
        Map<String, Object> result = new LinkedHashMap<>(mapping);
        result.put("ruleId", ruleId);
        result.put("profileId", selected.get("profile_id"));
        result.put("executionStatus", selected.get("execution_status"));
        result.put("executionBlockers", selected.get("execution_blockers"));
        result.put("hospitalId", hospitalId);
        result.put("items", items);
        result.put("metadataItems", metadataItems);
        result.put("relations", listOfMaps(mapping.get("relations")));
        result.put("ruleSource", "wiki");
        result.put("knowledgeReleaseId", resolved.releaseId());
        return result;
    }

    /**
     * 返回已审批且概览 SQL 至少通过静态门禁的候选口径。
     *
     * <p>知识发布状态仍可为 {@code documentation_only}；只要概览 SQL 来自当前发布
     * 快照、没有静态阻断并具备结果列映射，就允许进入后续双库受控试算。真实执行
     * 仍会再次校验统计周期、数据源和结果契约，不能据此绕过 DBHub 门禁。</p>
     */
    public List<Map<String, Object>> caliberProfiles(String ruleId, String hospitalId) {
        Map<String, Object> rule = findRule(ruleId);
        ResolvedManifest resolved = resolvedManifest(rule, hospitalId);
        return listOfMaps(resolved.document().get("profiles")).stream()
                .filter(profile -> "approved".equalsIgnoreCase(text(profile.get("governance_status"))))
                .filter(profile -> visibleToHospital(profile, hospitalId))
                .filter(profile -> {
                    Map<String, Object> refs = map(profile.get("sql_refs"));
                    Map<String, Object> capabilities = map(profile.get("sql_capabilities"));
                    String overview = validatedReferenceSql(
                            resolved.root(), refs, capabilities, "overview", "overview");
                    return overviewRuntimeEligible(
                            overview, capabilities, resultMapping(profile));
                })
                .map(profile -> {
                    Map<String, Object> value = new LinkedHashMap<>(profile);
                    value.put("status", "approved");
                    value.put("label", first(
                            text(profile.get("label")), text(profile.get("profile_name"))));
                    value.put("overviewRuntimeEligible", true);
                    value.put("parameterOverrides", map(profile.get("parameter_overrides")));
                    value.put("fieldRoleOverrides", map(profile.get("field_role_overrides")));
                    // 知识 Profile 内层仍是 snake 键；对外候选口径契约统一驼峰，
                    // 在这个边界一次性改名，与 MrasRuleKnowledgeSource 输出保持一致。
                    value.remove("parameter_overrides");
                    value.remove("field_role_overrides");
                    renameKey(value, "profile_id", "profileId");
                    renameKey(value, "profile_name", "profileName");
                    renameKey(value, "governance_status", "governanceStatus");
                    renameKey(value, "execution_status", "executionStatus");
                    renameKey(value, "execution_blockers", "executionBlockers");
                    renameKey(value, "numerator_rule", "numeratorRule");
                    renameKey(value, "denominator_rule", "denominatorRule");
                    renameKey(value, "time_dimension", "timeDimension");
                    renameKey(value, "effective_from", "effectiveFrom");
                    renameKey(value, "effective_to", "effectiveTo");
                    renameKey(value, "source_version", "sourceVersion");
                    renameKey(value, "source_level", "sourceLevel");
                    renameKey(value, "caliber_definition", "caliberDefinition");
                    renameKey(value, "period_anchor_label", "periodAnchorLabel");
                    renameKey(value, "elapsed_anchor_label", "elapsedAnchorLabel");
                    renameKey(value, "difference_dimensions", "differenceDimensions");
                    renameKey(value, "evidence_keywords", "evidenceKeywords");
                    renameKey(value, "baseline_equivalent", "baselineEquivalent");
                    // Profile中允许保留空的生效结束时间等可选字段，
                    // Map.copyOf 会因 null 值抛出异常，因此这里返回当前方法
                    // 已创建的独立可变副本；调用方无法借此修改缓存中的契约。
                    return value;
                })
                .toList();
    }

    /**
     * 返回全部可见 Profile 的安全展示目录，不读取 SQL 正文。
     *
     * <p>目录中的 {@code option_status} 是面向用户的四级分类：
     * 当前默认口径、可试算候选、仅可解释候选和草稿/未实现。草稿可以帮助用户理解
     * 资料中还有哪些方案，但永远不会被 {@link #caliberProfiles(String, String)}
     * 返回给执行链。</p>
     */
    public List<Map<String, Object>> caliberCatalog(String ruleId, String hospitalId) {
        Map<String, Object> rule = findRule(ruleId);
        ResolvedManifest resolved = resolvedManifest(rule, hospitalId);
        Map<String, Object> manifest = resolved.document();
        String defaultProfileId = text(manifest.get("default_profile"));
        List<Map<String, Object>> executable = caliberProfiles(ruleId, hospitalId);
        java.util.Set<String> executableIds = executable.stream()
                .map(item -> text(item.get("profileId")))
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> profile : listOfMaps(manifest.get("profiles"))) {
            if (!visibleToHospital(profile, hospitalId)) continue;
            String profileId = text(profile.get("profile_id"));
            boolean current = profileId.equals(defaultProfileId);
            String governance = text(profile.get("governance_status"));
            String optionStatus;
            if (current) {
                optionStatus = "current_default";
            } else if (executableIds.contains(profileId)) {
                optionStatus = "trial_available";
            } else if ("approved".equalsIgnoreCase(governance)) {
                optionStatus = "explanation_only";
            } else {
                optionStatus = "draft";
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("profileId", profileId);
            value.put("profileName", first(
                    text(profile.get("profile_name")), text(profile.get("label")), profileId));
            value.put("isCurrent", current);
            value.put("optionStatus", optionStatus);
            value.put("governanceStatus", governance);
            value.put("executionStatus", text(profile.get("execution_status")));
            value.put("overviewRuntimeEligible", executableIds.contains(profileId));
            value.put("unavailableReason", first(
                    String.join("；", stringList(profile.get("execution_blockers"))),
                    "draft".equals(optionStatus) ? "该方案仍是草稿或尚未实现" : ""));
            value.put("timeDimension", text(profile.get("time_dimension")));
            value.put("effectiveFrom", profile.get("effective_from"));
            value.put("effectiveTo", profile.get("effective_to"));
            result.add(value);
        }
        return List.copyOf(result);
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
        result.put("numeratorCaliber", profile.get("numerator_caliber"));
        result.put("denominatorCaliber", profile.get("denominator_caliber"));
        result.put("timeDimension", profile.get("time_dimension"));
        result.put("dedupKey", profile.get("dedup_key"));
        result.put("exclusions", first(
                text(profile.get("exclude_rule")),
                text(profile.get("exclusion_rule")),
                text(profile.get("exclusions"))));
        return result;
    }

    private Map<String, Object> mergedFieldMapping(
            Map<String, Object> profile, String ruleId, String hospitalId) {
        Map<String, Object> result = map(profile.get("field_mapping"));
        Map<String, Object> hospital = hospitalMapping(ruleId, hospitalId);
        if (!hospital.isEmpty()) {
            result.putAll(hospital);
        }
        // 知识库 YAML 仍用 snake 键；对外契约统一驼峰，在这个边界一次性改名。
        renameKey(result, "main_table", "mainTable");
        renameKey(result, "db_name", "dbName");
        renameKey(result, "query_profile", "queryProfile");
        result.putIfAbsent("status", "missing");
        result.putIfAbsent("dialect", "sqlserver");
        result.putIfAbsent("fields", Map.of());
        result.putIfAbsent("parameters", Map.of());
        result.putIfAbsent("relations", List.of());
        result.putIfAbsent("queryProfile", "");
        return result;
    }

    private static void renameKey(Map<String, Object> value, String from, String to) {
        if (value.containsKey(from)) {
            value.putIfAbsent(to, value.remove(from));
        }
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
            /*
             * 显式 Profile 编号也可能来自当前轮已经准备好的静态概览试算对象。此时
             * 仍以 documentation_only 视图回读，保证准备与执行阶段的上下文指纹一致；
             * 候选口径列表继续过滤 draft，不能把草稿伪装成已审批口径。
             */
            return "draft".equalsIgnoreCase(text(selected.get("execution_status")))
                    ? documentationFallback(List.of(selected))
                    : selected;
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
     * 只有草稿方案时仍允许用户查阅指标文档。草稿的治理状态不能伪装成已发布，但其
     * 已通过静态门禁的 SQL 引用和结果列候选仍可用于“参考 SQL”以及受控双库试算；
     * 真实执行若失败必须返回明确错误，不能把试算结果描述为已审批生效口径。
     */
    private Map<String, Object> documentationFallback(List<Map<String, Object>> profiles) {
        Map<String, Object> source = profiles.isEmpty() ? Map.of() : profiles.get(0);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("profile_id", text(source.get("profile_id")));
        fallback.put("profile_name", first(
                text(source.get("profile_name")), "指标文档（暂无已审批生效口径）"));
        fallback.put("governance_status", "documentation_only");
        fallback.put("execution_status", "documentation_only");
        fallback.put("execution_blockers", List.of("当前指标没有可进入生效口径的已审批Profile"));
        fallback.put("numerator_rule", text(source.get("numerator_rule")));
        fallback.put("numerator_caliber", text(source.get("numerator_caliber")));
        fallback.put("denominator_rule", text(source.get("denominator_rule")));
        fallback.put("denominator_caliber", text(source.get("denominator_caliber")));
        fallback.put("time_dimension", text(source.get("time_dimension")));
        fallback.put("dedup_key", text(source.get("dedup_key")));
        fallback.put("sql_refs", map(source.get("sql_refs")));
        fallback.put("sql_capabilities", map(source.get("sql_capabilities")));
        fallback.put("result_mapping", map(source.get("result_mapping")));
        fallback.put("result_mapping_candidates", map(source.get("result_mapping_candidates")));
        fallback.put("dual_database_contract", map(source.get("dual_database_contract")));
        fallback.put("field_contract", map(source.get("field_contract")));
        fallback.put("field_mapping", map(source.get("field_mapping")));
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
        result.put("ruleId", text(rule.get("rule_id")));
        result.put("ruleName", text(rule.get("rule_name")));
        result.put("category", text(rule.get("category")));
        result.put("content", first(text(rule.get("definition_short")),
                section(read(path), "指标定义")));
        result.put("formula", text(rule.get("formula_short")));
        result.put("numerator", text(rule.get("numerator_short")));
        result.put("denominator", text(rule.get("denominator_short")));
        result.put("timeDimension", text(rule.get("time_dimension")));
        result.put("executionStatus", text(rule.get("execution_status")));
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

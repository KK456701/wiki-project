package com.hospital.wikiagent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class WikiRuleKnowledgeSourceTest {
    private final WikiRuleKnowledgeSource source = new WikiRuleKnowledgeSource(
            Path.of("src", "main", "resources", "core-rules-wiki").toString(),
            new ObjectMapper());

    @Test
    void readsStaticOverviewReferenceWithoutPretendingProductionContractIsComplete() {
        Map<String, Object> rule = source.effectiveRule(
                "患者入院 48 小时内转科的比例", "hospital_without_release");

        assertThat(rule.get("ruleId")).isEqualTo("HXZD-001-001");
        assertThat(rule.get("ruleSource")).isEqualTo("wiki");
        assertThat(rule.get("effectiveLevel")).isEqualTo("company");
        assertThat(rule.get("executionStatus")).isEqualTo("documentation_only");
        assertThat(rule.get("sqlStatus")).isEqualTo("unavailable");
        assertThat(rule.get("standardSql")).asString()
                .contains("MRAS_BUSINESS_FIRSTVISIT");
        assertThat(rule.get("overviewRuntimeEligible")).isEqualTo(false);
        assertThat(rule.get("numeratorRule")).isEqualTo("入院48小时内转科患者人次数");
        assertThat(rule.get("denominatorRule")).isEqualTo("同期入院患者总人次数");
        assertThat(rule.get("filterRule")).asString().isBlank();
        assertThat(rule.get("excludeRule")).asString().isBlank();
        assertThat(((Map<?, ?>) rule.get("calculationDefinition")).get("exclusions"))
                .asString().isBlank();
    }

    @Test
    void fuzzySearchAndFieldMappingDoNotNeedRuntimeDatabase() {
        Map<String, Object> search = source.searchForHospital(
                "入院48小时转科比例", "hospital_without_release", 5);
        Map<String, Object> mapping = source.fieldMapping(
                "HXZD-001-001", "hospital_without_release");

        assertThat(search.get("resolvedRuleId")).isEqualTo("HXZD-001-001");
        assertThat(mapping.get("ruleSource")).isEqualTo("wiki");
        assertThat(mapping.get("executionStatus")).isEqualTo("documentation_only");
        assertThat(mapping.get("status")).isEqualTo("missing");
        assertThat((List<?>) mapping.get("items")).isEmpty();
    }

    @Test
    void exposesAllIndicatorsButNoUnverifiedProfilesForExecution() {
        var indicators = source.activeIndicatorNames("hospital_without_release", 100);
        var profiles = source.diagnosticProfiles(
                "HXZD-001-001", "hospital_without_release");
        var qualityRules = source.dataQualityRules("HXZD-001-001");

        assertThat(indicators).hasSize(35);
        assertThat(indicators).extracting(item -> item.get("ruleId"))
                .contains("HXZD-001-001", "HXZD-016-002");
        assertThat(profiles).isEmpty();
        assertThat(qualityRules).isEmpty();
    }

    @Test
    void hospitalReleaseOverridesCompanyDocumentationProfile() throws IOException {
        Map<String, Object> rule = source.effectiveRule(
                "患者入院 48 小时内转科的比例", "hospital_001");
        Map<?, ?> pointer = new ObjectMapper().readValue(
                Path.of("src", "main", "resources", "core-rules-wiki",
                        "pointers", "hospitals",
                        "hospital_001-current.json").toFile(),
                Map.class);

        assertThat(rule)
                .containsEntry("executionStatus", "executable")
                .containsEntry("sqlStatus", "available")
                .containsEntry("knowledgeReleaseId", pointer.get("release_id"));
        assertThat(rule.get("standardSql")).asString().contains("MRAS_BUSINESS_FIRSTVISIT");
        Map<?, ?> extraction = (Map<?, ?>) rule.get("extractionContract");
        assertThat(extraction.get("route")).isEqualTo("EVENT");
        assertThat(extraction.get("event_table"))
                .isEqualTo("MRAS_BUSINESS_FIRSTVISIT");
        assertThat(source.fieldMapping("HXZD-001-001", "hospital_001"))
                .containsEntry("status", "confirmed");
    }

    @Test
    void explicitScalarOverviewMappingIsNotPollutedByGeneratedCandidates() {
        Map<String, Object> rule = source.effectiveRule(
                "HXZD-012-001", "hospital_001");
        Map<?, ?> contract = (Map<?, ?>) rule.get("dualDatabaseContract");
        Map<?, ?> mapping = (Map<?, ?>) contract.get("overview_result_mapping");

        assertThat(mapping.get("index_value")).isEqualTo("index_value");
        assertThat(mapping.get("component_left"))
                .isEqualTo("分子-四级手术并发症发生率");
        assertThat(mapping.get("component_right"))
                .isEqualTo("分母-三级手术并发症发生率");
        assertThat(mapping.containsKey("numerator_count")).isFalse();
        assertThat(mapping.containsKey("denominator_count")).isFalse();
        assertThat(rule.get("resultUnit")).isEqualTo("ratio");
    }

    @Test
    void medianMetricPublishesMinuteResultContractAndTargetMapping() {
        Map<String, Object> rule = source.effectiveRule(
                "HXZD-014-001", "hospital_001");
        Map<?, ?> resultContract = (Map<?, ?>) rule.get("resultContract");
        Map<?, ?> dual = (Map<?, ?>) rule.get("dualDatabaseContract");
        Map<?, ?> mapping = (Map<?, ?>) dual.get("overview_result_mapping");

        assertThat(rule.get("resultUnit")).isEqualTo("minutes");
        assertThat(resultContract.get("value_type")).isEqualTo("median_duration");
        assertThat(resultContract.get("target_value")).isEqualTo(5);
        assertThat(mapping.get("index_value")).isEqualTo("监测情况");
        assertThat(mapping.get("sample_count")).isEqualTo("sample_count");
        assertThat(mapping.get("target_value")).isEqualTo("目标值");
    }

    @Test
    void allThirtyFiveIndicatorsExposeHumanReadableDefinitionAndCalculationTerms() {
        var indicators = source.activeIndicatorNames("hospital_001", 100);

        for (Map<String, String> indicator : indicators) {
            Map<String, Object> rule = source.effectiveRule(indicator.get("ruleId"), "hospital_001");
            assertThat(rule.get("definition"))
                    .as("%s definition", indicator.get("ruleId"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("formula"))
                    .as("%s formula", indicator.get("ruleId"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("numeratorRule"))
                    .as("%s numerator", indicator.get("ruleId"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("denominatorRule"))
                    .as("%s denominator", indicator.get("ruleId"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("standardSql"))
                    .as("%s overview SQL", indicator.get("ruleId"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("overviewRuntimeEligible"))
                    .as("%s overview runtime eligibility", indicator.get("ruleId"))
                    .isEqualTo(true);
        }
    }

    @Test
    void draftOnlyIndicatorRemainsDocumentationOnlyButKeepsStaticOverviewReference() {
        Map<String, Object> rule = source.effectiveRule("HXZD-009-004", "hospital_001");

        assertThat(rule.get("ruleId")).isEqualTo("HXZD-009-004");
        assertThat(rule.get("profileId"))
                .isEqualTo("HXZD-009-004-company-default");
        assertThat(rule.get("executionStatus")).isEqualTo("documentation_only");
        assertThat(rule.get("sqlStatus")).isEqualTo("overview_static_validated");
        assertThat(rule.get("standardSql")).asString().isNotBlank();
        assertThat(rule.get("overviewRuntimeEligible")).isEqualTo(true);
        assertThat(rule.get("executionBlockers")).asList()
                .containsExactly("当前指标没有可进入生效口径的已审批Profile");
    }

    @Test
    void atomicallyReloadsACompleteReleaseAndKeepsThePreviousSnapshotOnInvalidPointer(
            @TempDir Path root) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        createSearchOnlyRelease(root, "KB-ONE", "第一版指标", mapper);
        createSearchOnlyRelease(root, "KB-TWO", "第二版指标", mapper);
        writePointer(root, "KB-ONE", mapper);

        WikiRuleKnowledgeSource versioned = new WikiRuleKnowledgeSource(root.toString(), mapper);
        assertThat(versioned.searchForHospital("第一版指标", "hospital_001", 3)
                .get("knowledgeReleaseId")).isEqualTo("KB-ONE");

        // 运行时每秒最多检查一次指针，防止每个请求都访问磁盘。
        Thread.sleep(1_050L);
        writePointer(root, "KB-TWO", mapper);
        Map<String, Object> switched = versioned.searchForHospital("第二版指标", "hospital_001", 3);
        assertThat(switched.get("knowledgeReleaseId")).isEqualTo("KB-TWO");
        assertThat(switched.get("resolvedRuleId")).isEqualTo("HXZD-001-001");

        // 指向不存在的版本时不得清空当前缓存，也不能退回到半成品目录。
        Thread.sleep(1_050L);
        Files.writeString(root.resolve("pointers/company-current.json"), """
                {"schema_version":"knowledge-pointer-v1","release_id":"KB-BROKEN",
                 "release_path":"releases/company/KB-BROKEN"}
                """, StandardCharsets.UTF_8);
        Map<String, Object> retained = versioned.searchForHospital("第二版指标", "hospital_001", 3);
        assertThat(retained.get("knowledgeReleaseId")).isEqualTo("KB-TWO");
        assertThat(retained.get("resolvedRuleId")).isEqualTo("HXZD-001-001");
    }

    private static void createSearchOnlyRelease(
            Path root, String releaseId, String ruleName, ObjectMapper mapper) throws Exception {
        Path release = root.resolve("releases/company").resolve(releaseId);
        Path indexes = release.resolve("indexes");
        Files.createDirectories(indexes);
        Map<String, Object> rule = Map.of(
                "rule_id", "HXZD-001-001",
                "rule_name", ruleName,
                "status", "active",
                "aliases", List.of(),
                "keywords", List.of());
        writeJson(indexes.resolve("rule_index.json"), Map.of(
                "schema_version", "hxzd-rule-index-v2",
                "release_id", releaseId,
                "rules", List.of(rule)), mapper);
        writeJson(indexes.resolve("retrieval_cards.json"), Map.of(
                "schema_version", "hxzd-retrieval-cards-v1",
                "release_id", releaseId,
                "cards", List.of(rule)), mapper);
        Map<String, String> hashes = Map.of(
                "indexes/rule_index.json", sha256(indexes.resolve("rule_index.json")),
                "indexes/retrieval_cards.json", sha256(indexes.resolve("retrieval_cards.json")));
        writeJson(release.resolve("release-manifest.json"), Map.of(
                "schema_version", "knowledge-release-v2",
                "release_id", releaseId,
                "files", hashes), mapper);
    }

    private static void writePointer(Path root, String releaseId, ObjectMapper mapper) throws IOException {
        Path pointer = root.resolve("pointers/company-current.json");
        Files.createDirectories(pointer.getParent());
        writeJson(pointer, Map.of(
                "schema_version", "knowledge-pointer-v1",
                "release_id", releaseId,
                "release_path", "releases/company/" + releaseId), mapper);
    }

    private static void writeJson(Path path, Object value, ObjectMapper mapper) throws IOException {
        Files.createDirectories(path.getParent());
        mapper.writeValue(path.toFile(), value);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}

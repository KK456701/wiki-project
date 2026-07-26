package com.hospital.wikiagent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 验证规则仓储只消费 HXZD Wiki；测试不再创建或依赖旧 MQSI 表。
 */
class RuleReadRepositoryTest {
    private EmbeddedDatabase database;
    private RuleReadRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setName("wiki_rules_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .build();
        ObjectMapper objectMapper = new ObjectMapper();
        WikiRuleKnowledgeSource wiki = new WikiRuleKnowledgeSource(
                Path.of("..", "core-rules-wiki").toString(), objectMapper);
        repository = new RuleReadRepository(new JdbcTemplate(database), wiki);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.shutdown();
    }

    @Test
    void searchesAllHxzdIndicatorsFromWiki() {
        assertThat(repository.activeIndicatorNames("hospital_001", 100))
                .hasSize(35)
                .allSatisfy(item -> assertThat(item.get("rule_id")).startsWith("HXZD-"));
        assertThat(repository.searchForHospital("首诊", "hospital_001", 5))
                .extracting(result -> result.get("resolved_rule_id"))
                .isEqualTo("HXZD-001-001");
    }

    @Test
    void returnsDocumentationProfileWithStaticOverviewReference() {
        var rule = repository.effectiveRule(
                "HXZD-001-001", "hospital_without_release");

        assertThat(rule)
                .containsEntry("rule_id", "HXZD-001-001")
                .containsEntry("execution_status", "documentation_only")
                .containsEntry("sql_status", "unavailable")
                .containsEntry("overview_runtime_eligible", false);
        assertThat(rule.get("standard_sql")).asString()
                .contains("MRAS_BUSINESS_FIRSTVISIT");
        assertThat(rule.get("numerator_rule")).asString().isNotBlank();
        assertThat(rule.get("denominator_rule")).asString().isNotBlank();
    }

    @Test
    void doesNotExposeUnverifiedProfilesForExecution() {
        assertThat(repository.caliberProfiles(
                "HXZD-001-001", "hospital_without_release")).isEmpty();
        assertThat(repository.diagnosticProfiles(
                "HXZD-001-001", "hospital_without_release")).isEmpty();
    }

    @Test
    void previewIsReadOnlyAndNamesCurrentProfile() {
        var preview = repository.previewChange(
                "HXZD-001-001", "hospital_001", "将首诊时间改为15分钟");

        assertThat(preview)
                .containsEntry("rule_id", "HXZD-001-001")
                .containsKey("profile_id")
                .containsEntry("target_level", "hospital");
        assertThat(preview.get("message")).asString().contains("不提供草稿、审批或发布");
    }
}

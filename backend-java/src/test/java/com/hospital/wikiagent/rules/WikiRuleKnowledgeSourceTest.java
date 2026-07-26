package com.hospital.wikiagent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class WikiRuleKnowledgeSourceTest {
    private final WikiRuleKnowledgeSource source = new WikiRuleKnowledgeSource(
            Path.of("..", "core-rules-wiki").toString(), new ObjectMapper());

    @Test
    void readsHxzdRuleAsDocumentationOnlyWhenExecutionContractIsMissing() {
        Map<String, Object> rule = source.effectiveRule("患者入院 48 小时内转科的比例", "hospital_001");

        assertThat(rule.get("rule_id")).isEqualTo("HXZD-001-001");
        assertThat(rule.get("rule_source")).isEqualTo("wiki");
        assertThat(rule.get("effective_level")).isEqualTo("company");
        assertThat(rule.get("execution_status")).isEqualTo("documentation_only");
        assertThat(rule.get("sql_status")).isEqualTo("unavailable");
        assertThat(rule.get("standard_sql")).isEqualTo("");
        assertThat(rule.get("numerator_rule")).isEqualTo("入院48小时内转科患者人次数");
        assertThat(rule.get("denominator_rule")).isEqualTo("同期入院患者总人次数");
    }

    @Test
    void fuzzySearchAndFieldMappingDoNotNeedRuntimeDatabase() {
        Map<String, Object> search = source.searchForHospital("入院48小时转科比例", "hospital_001", 5);
        Map<String, Object> mapping = source.fieldMapping("HXZD-001-001", "hospital_001");

        assertThat(search.get("resolved_rule_id")).isEqualTo("HXZD-001-001");
        assertThat(mapping.get("rule_source")).isEqualTo("wiki");
        assertThat(mapping.get("execution_status")).isEqualTo("documentation_only");
        assertThat(mapping.get("status")).isEqualTo("missing");
        assertThat((List<?>) mapping.get("items")).isEmpty();
    }

    @Test
    void exposesAllIndicatorsButNoUnverifiedProfilesForExecution() {
        var indicators = source.activeIndicatorNames("hospital_001", 100);
        var profiles = source.diagnosticProfiles("HXZD-001-001", "hospital_001");
        var qualityRules = source.dataQualityRules("HXZD-001-001");

        assertThat(indicators).hasSize(35);
        assertThat(indicators).extracting(item -> item.get("rule_id"))
                .contains("HXZD-001-001", "HXZD-016-002");
        assertThat(profiles).isEmpty();
        assertThat(qualityRules).isEmpty();
    }

    @Test
    void allThirtyFiveIndicatorsExposeHumanReadableDefinitionAndCalculationTerms() {
        var indicators = source.activeIndicatorNames("hospital_001", 100);

        for (Map<String, String> indicator : indicators) {
            Map<String, Object> rule = source.effectiveRule(indicator.get("rule_id"), "hospital_001");
            assertThat(rule.get("definition"))
                    .as("%s definition", indicator.get("rule_id"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("formula"))
                    .as("%s formula", indicator.get("rule_id"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("numerator_rule"))
                    .as("%s numerator", indicator.get("rule_id"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
            assertThat(rule.get("denominator_rule"))
                    .as("%s denominator", indicator.get("rule_id"))
                    .isInstanceOf(String.class)
                    .asString()
                    .isNotBlank();
        }
    }

    @Test
    void draftOnlyIndicatorRemainsSearchableButDraftDoesNotBecomeEffectiveProfile() {
        Map<String, Object> rule = source.effectiveRule("HXZD-009-004", "hospital_001");

        assertThat(rule.get("rule_id")).isEqualTo("HXZD-009-004");
        assertThat(rule.get("profile_id")).isEqualTo("");
        assertThat(rule.get("profile_name")).isEqualTo("指标文档（暂无已审批生效口径）");
        assertThat(rule.get("execution_status")).isEqualTo("documentation_only");
        assertThat(rule.get("sql_status")).isEqualTo("unavailable");
        assertThat(rule.get("execution_blockers")).asList()
                .containsExactly("当前指标没有可进入生效口径的已审批Profile");
    }
}

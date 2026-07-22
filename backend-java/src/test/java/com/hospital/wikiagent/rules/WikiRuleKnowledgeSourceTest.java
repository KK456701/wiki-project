package com.hospital.wikiagent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class WikiRuleKnowledgeSourceTest {
    private final WikiRuleKnowledgeSource source = new WikiRuleKnowledgeSource(
            Path.of("..", "core-rules-wiki").toString(), new ObjectMapper());

    @Test
    void readsHospitalRuleAndImplementationDirectlyFromWiki() {
        Map<String, Object> rule = source.effectiveRule("患者入院 48 小时内转科的比例", "hospital_001");

        assertThat(rule.get("rule_id")).isEqualTo("MQSI2025_001");
        assertThat(rule.get("rule_source")).isEqualTo("wiki");
        assertThat(rule.get("effective_level")).isEqualTo("hospital");
        assertThat(rule.get("hospital_version")).isEqualTo(4);
        assertThat(rule.get("standard_sql").toString()).contains("INPATIENT_ENCOUNTER");
        assertThat(objectMap(rule.get("effective_params")))
                .containsEntry("transfer_minutes_threshold", 2880);
    }

    @Test
    void fuzzySearchAndFieldMappingDoNotNeedRuntimeDatabase() {
        Map<String, Object> search = source.searchForHospital("入院48小时转科比例", "hospital_001", 5);
        Map<String, Object> mapping = source.fieldMapping("MQSI2025_005", "hospital_001");

        assertThat(search.get("resolved_rule_id")).isEqualTo("MQSI2025_001");
        assertThat(mapping.get("rule_source")).isEqualTo("wiki");
        assertThat(objectMap(mapping.get("parameters")))
                .containsEntry("arrive_minutes_threshold", 20);
        assertThat((java.util.List<?>) mapping.get("items")).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }
}

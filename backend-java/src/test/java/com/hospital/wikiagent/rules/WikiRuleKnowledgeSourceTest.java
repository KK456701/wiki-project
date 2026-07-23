package com.hospital.wikiagent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    @Test
    void readsOnlyApprovedVisibleDiagnosisProfilesAndQualityRules() {
        var consultProfiles = source.diagnosticProfiles("MQSI2025_005", "hospital_001");
        var otherHospitalProfiles = source.diagnosticProfiles("MQSI2025_005", "hospital_999");
        var transferProfiles = source.diagnosticProfiles("MQSI2025_001", "hospital_001");
        var qualityRules = source.dataQualityRules("MQSI2025_001");

        assertThat(consultProfiles).extracting(profile -> profile.get("profile_id"))
                .containsExactly("national_2025_10m", "hospital_001_20m");
        assertThat(otherHospitalProfiles).extracting(profile -> profile.get("profile_id"))
                .containsExactly("national_2025_10m");
        assertThat(transferProfiles).extracting(profile -> profile.get("profile_id"))
                .containsExactly("national_2025", "hospital_001_ward_entry_anchor");
        assertThat(transferProfiles.get(1).get("field_role_overrides").toString())
                .contains("period_time", "ward_entry_time", "admit_time");
        assertThat(qualityRules).extracting(rule -> rule.get("type"))
                .contains("required_not_null", "duplicate_key", "timestamp_order");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }
}

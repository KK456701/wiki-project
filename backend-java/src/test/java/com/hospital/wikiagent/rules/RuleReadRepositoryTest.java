package com.hospital.wikiagent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.fasterxml.jackson.databind.ObjectMapper;

class RuleReadRepositoryTest {
    private JdbcTemplate jdbc;
    private RuleReadRepository repository;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder()
                .setName("rules_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new RuleReadRepository(jdbc, new ObjectMapper());
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                "INSERT INTO med_index_standard "
                        + "(index_code,index_name,index_type,index_desc,stat_cycle,numerator_rule,denominator_rule,"
                        + "filter_rule,exclude_rule,rely_table_field,calculation_definition,standard_sql,rule_params,"
                        + "source_path,version,status,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "MQSI2025_001", "患者入院 48 小时内转科的比例", "三级查房制度", "标准定义", "month",
                "标准分子", "标准分母", "", "", "{\"business_fields\":{}}",
                "{\"schema_version\":1,\"numerator\":{\"conditions\":[]}}", "SELECT 1",
                "{\"threshold\":48}", "rules/source.yml", "2025", 1, now, now);
        jdbc.update(
                "INSERT INTO med_index_hospital_custom "
                        + "(hospital_id,index_code,custom_numerator,custom_denominator,custom_filter,exclude_rule,"
                        + "custom_params,custom_calculation_patch,custom_sql,version,status,approval_status,"
                        + "effective_from,effective_to,oper_user,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "hospital_001", "MQSI2025_001", "本院分子", null, null, null,
                "{\"threshold\":72}", "{\"numerator\":{\"conditions\":[{\"id\":\"local\"}]}}",
                null, 4, 1, "approved", now.minusDays(1), null, "admin", now, now);
    }

    @Test
    void mergesOnlyCurrentHospitalOverride() {
        Map<String, Object> hospitalOne = repository.effectiveRule("MQSI2025_001", "hospital_001");
        Map<String, Object> hospitalTwo = repository.effectiveRule("MQSI2025_001", "hospital_002");

        assertThat(hospitalOne.get("effective_level")).isEqualTo("hospital");
        assertThat(hospitalOne.get("numerator_rule")).isEqualTo("本院分子");
        assertThat(hospitalOne.get("hospital_version")).isEqualTo(4);
        assertThat(hospitalTwo.get("effective_level")).isEqualTo("national");
        assertThat(hospitalTwo.get("numerator_rule")).isEqualTo("标准分子");
        assertThat(hospitalTwo.get("hospital_version")).isNull();
    }

    @Test
    void searchIsHospitalScopedAndKeepsPublicContractShape() {
        Map<String, Object> response = repository.searchForHospital("转科", "hospital_001", 5);

        assertThat(response).containsKeys("query", "resolved_rule_id", "matches");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) response.get("matches");
        assertThat(matches).extracting(item -> item.get("rule_id")).containsExactly("MQSI2025_001");
        assertThat(matches.get(0)).containsKeys("rule_name", "category", "content", "type");
    }

    @Test
    void searchTreatsPlannerWhitespaceNormalizationAsEquivalent() {
        Map<String, Object> response = repository.searchForHospital(
                "患者入院48小时内转科的比例", "hospital_001", 5);

        assertThat(response.get("resolved_rule_id")).isEqualTo("MQSI2025_001");
    }

    @Test
    void searchTreatsPlannerFunctionWordOmissionAsEquivalent() {
        Map<String, Object> response = repository.searchForHospital(
                "患者入院48小时内转科比例", "hospital_001", 5);

        assertThat(response.get("resolved_rule_id")).isEqualTo("MQSI2025_001");
    }

    @Test
    void searchTreatsPlannerSubjectOmissionAsEquivalent() {
        Map<String, Object> response = repository.searchForHospital(
                "入院48小时内转科比例", "hospital_001", 5);

        assertThat(response.get("resolved_rule_id")).isEqualTo("MQSI2025_001");
    }

    @Test
    void previewsRuleChangeWithoutWritingCurrentRule() {
        Integer before = jdbc.queryForObject(
                "SELECT version FROM med_index_hospital_custom WHERE hospital_id=? AND index_code=?",
                Integer.class, "hospital_001", "MQSI2025_001");

        Map<String, Object> preview = repository.previewChange(
                "MQSI2025_001", "hospital_001", "本院改为72分钟内完成转科");

        assertThat(preview).containsEntry("rule_id", "MQSI2025_001")
                .containsEntry("target_level", "hospital");
        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) preview.get("impact");
        assertThat(impact).containsEntry("requires_version_increment", true)
                .containsEntry("requires_sql_regeneration", true);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM med_index_hospital_custom WHERE hospital_id=? AND index_code=?",
                Integer.class, "hospital_001", "MQSI2025_001")).isEqualTo(before);
    }
}

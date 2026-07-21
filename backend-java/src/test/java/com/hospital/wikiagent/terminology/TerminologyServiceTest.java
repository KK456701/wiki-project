package com.hospital.wikiagent.terminology;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class TerminologyServiceTest {
    private JdbcTemplate jdbc;
    private TerminologyService service;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder()
                .setName("terminology_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        service = new TerminologyService(new TerminologyRepository(jdbc));
        insertConcept("TERM_URGENT", "急会诊及时到位率", "indicator");
        insertAlias("", "TERM_URGENT", "急会诊到位率", "abbreviation", true, true);
        jdbc.update("INSERT INTO med_term_rule_link "
                        + "(concept_code,index_code,usage_section,business_field_key,source_reference,version) "
                        + "VALUES (?,?,?,?,?,?)",
                "TERM_URGENT", "MQSI2025_005", "rule_name", null, "test", 1);
        jdbc.update("INSERT INTO med_term_release "
                        + "(release_id,version,status,checksum,snapshot_json,change_summary,published_by,published_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                "TERMREL_001", 1, "active", "abc", "{}", "test", "admin", LocalDateTime.now());
    }

    @Test
    void listsConceptsByApprovedAliasAndRule() {
        Map<String, Object> response = service.listConcepts("到位率", "", "MQSI2025_005");

        assertThat(response).containsEntry("total", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
        assertThat(items.get(0)).containsEntry("concept_code", "TERM_URGENT")
                .containsEntry("alias_count", 1);
    }

    @Test
    void normalizesLongestApprovedTermAndReportsSqlEligibility() {
        Map<String, Object> result = service.normalize("请查急会诊到位率", "hospital_001");

        assertThat(result).containsEntry("normalized_text", "请查急会诊及时到位率")
                .containsEntry("release_version", "TERMREL_001")
                .containsEntry("sql_eligible", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertThat(matches).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("source", "company")
                .containsEntry("concept_code", "TERM_URGENT"));
    }

    @Test
    void reportsAmbiguousAliasInsteadOfChoosingAConcept() {
        insertConcept("TERM_OTHER", "急诊会诊", "business_concept");
        insertAlias("", "TERM_OTHER", "急会诊到位率", "related", true, false);

        Map<String, Object> result = service.normalize("急会诊到位率", "hospital_001");

        assertThat(result).containsEntry("sql_eligible", false);
        assertThat((List<?>) result.get("matches")).isEmpty();
        assertThat((List<?>) result.get("ambiguities")).hasSize(1);
    }

    private void insertConcept(String code, String name, String type) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO med_term_concept "
                        + "(concept_code,canonical_name,concept_type,definition,standard_code,source_level,"
                        + "source_reference,version,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                code, name, type, "定义", null, "national", "test", 1, "active", now, now);
    }

    private void insertAlias(
            String hospitalId, String code, String alias, String relation,
            boolean retrieval, boolean sqlSafe) {
        jdbc.update("INSERT INTO med_term_alias "
                        + "(hospital_id,concept_code,alias_text,relation_type,retrieval_enabled,sql_safe,"
                        + "ambiguity_group,source_reference,approval_status,version,created_by,approved_by,created_at,approved_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                hospitalId, code, alias, relation, retrieval ? 1 : 0, sqlSafe ? 1 : 0,
                null, "test", "approved", 1, "admin", "admin", LocalDateTime.now(), LocalDateTime.now());
    }
}

package com.hospital.wikiagent.terminology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import tools.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.terminology.TerminologyGovernanceService.AliasCommand;
import com.hospital.wikiagent.terminology.TerminologyGovernanceService.MappingCommand;

class TerminologyGovernanceServiceTest {
    private JdbcTemplate jdbc;
    private TerminologyGovernanceService service;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder()
                .setName("terminology_governance_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        service = new TerminologyGovernanceService(jdbc, new ObjectMapper());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("INSERT INTO med_term_concept "
                        + "(concept_code,canonical_name,concept_type,definition,standard_code,source_level,"
                        + "source_reference,version,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "TERM_001", "急会诊及时到位率", "indicator", "定义", null,
                "national", "test", 1, "active", now, now);
        jdbc.update("INSERT INTO med_term_rule_link "
                        + "(concept_code,index_code,usage_section,business_field_key,source_reference,version) "
                        + "VALUES (?,?,?,?,?,?)",
                "TERM_001", "MQSI2025_005", "rule_name", null, "test", 1);
    }

    @Test
    void createsAndApprovesHospitalAliasWithAudit() {
        Map<String, Object> created = service.createAlias(new AliasCommand(
                "hospital_001", "TERM_001", "急会诊到位率", "abbreviation",
                true, true, null, "workbench"));
        Map<String, Object> approved = service.approveAlias(
                ((Number) created.get("id")).longValue(), "hospital_001");

        assertThat(created).containsEntry("approval_status", "pending");
        assertThat(approved).containsEntry("approval_status", "approved")
                .containsEntry("approved_by", "admin");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM med_term_audit_log", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rejectsUnsafeRelationAndCrossHospitalApproval() {
        assertThatThrownBy(() -> service.createAlias(new AliasCommand(
                "", "TERM_001", "相关说法", "related", true, true, null, "test")))
                .isInstanceOf(TerminologyGovernanceException.class)
                .hasMessageContaining("不能用于 SQL");

        Map<String, Object> created = service.createAlias(new AliasCommand(
                "hospital_001", "TERM_001", "院内简称", "colloquial",
                true, false, null, "test"));
        assertThatThrownBy(() -> service.approveAlias(
                ((Number) created.get("id")).longValue(), "hospital_002"))
                .isInstanceOf(TerminologyGovernanceException.class)
                .hasMessageContaining("当前登录医院");
    }

    @Test
    void createsAndApprovesMappingWithImmutableVersion() {
        Map<String, Object> created = service.createMapping(new MappingCommand(
                "hospital_001", "TERM_001", "hospital_consult", "URG",
                "急会诊", "977578", null, null), "hospital_001");
        Map<String, Object> approved = service.approveMapping(
                ((Number) created.get("id")).longValue(), "hospital_001");

        assertThat(approved).containsEntry("approval_status", "approved");
        assertThat(String.valueOf(approved.get("version_id"))).startsWith("TMV_");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_hospital_term_mapping_version", Integer.class)).isEqualTo(1);
    }

    @Test
    void publishRequiresReviewThenReusesSameSnapshotAndRestores() {
        Map<String, Object> created = service.createAlias(new AliasCommand(
                "", "TERM_001", "急会诊到位率", "abbreviation", true, true, null, "test"));
        assertThatThrownBy(service::publish)
                .isInstanceOf(TerminologyGovernanceException.class)
                .hasMessageContaining("待审核");
        service.approveAlias(((Number) created.get("id")).longValue(), "");

        Map<String, Object> first = service.publish();
        Map<String, Object> second = service.publish();
        Map<String, Object> restored = service.restore(String.valueOf(first.get("release_id")));

        assertThat(first).containsEntry("reused", false);
        assertThat(second).containsEntry("reused", true)
                .containsEntry("release_id", first.get("release_id"));
        assertThat(restored).containsEntry("active_release_id", first.get("release_id"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_term_alias WHERE approval_status='approved'", Integer.class))
                .isEqualTo(1);
    }
}

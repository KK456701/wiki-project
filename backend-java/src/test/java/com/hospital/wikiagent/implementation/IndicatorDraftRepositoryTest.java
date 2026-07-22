package com.hospital.wikiagent.implementation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

class IndicatorDraftRepositoryTest {
    private JdbcTemplate jdbc;
    private IndicatorDraftRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("draft-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        repository = new IndicatorDraftRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        insert("DRAFT_1", "hospital_001", "requirements_pending", 1, "{}", null);
        insert("DRAFT_2", "hospital_002", "requirements_pending", 1, "{}", null);
    }

    @Test
    void editsDraftWithOptimisticLockAndInvalidatesSqlEvidence() {
        Map<String, Object> saved = repository.update("DRAFT_1", "hospital_001", 1,
                Map.of("index_name", "新版指标", "metadata_requirements", List.of("admission_id")), "user_001");

        assertThat(saved).containsEntry("index_name", "新版指标")
                .containsEntry("status", "requirements_pending")
                .containsEntry("current_version", 2);
        assertThat(saved.get("metadata_requirements")).isEqualTo(List.of("admission_id"));
        assertThat(saved.get("current_sql")).isNull();
        assertThat(repository.versions("DRAFT_1", "hospital_001")).hasSize(1);
        assertThat(repository.list("hospital_001", null)).hasSize(1);
        assertThat(repository.list("hospital_002", null)).hasSize(1);
        assertThatThrownBy(() -> repository.update("DRAFT_1", "hospital_001", 1,
                Map.of("index_name", "过期修改"), "user_001"))
                .isInstanceOf(ImplementationException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void confirmsRequirementsAndOnlySubmitsCurrentTrialEvidence() {
        Map<String, Object> confirmed = repository.confirmRequirements(
                "DRAFT_1", "hospital_001", 1, "user_001");
        assertThat(confirmed).containsEntry("status", "metadata_pending").containsEntry("current_version", 2);

        jdbc.update("""
                UPDATE med_indicator_draft SET status='trial_passed',current_version=5,
                  trial_result='{"status":"success","run_id":"RUN_1"}',trial_draft_version=5
                WHERE draft_id='DRAFT_1'
                """);
        Map<String, Object> submitted = repository.submit("DRAFT_1", "hospital_001", 5, "user_001");
        assertThat(submitted).containsEntry("status", "pending_approval")
                .containsEntry("current_version", 6).containsEntry("trial_draft_version", 6);

        jdbc.update("""
                UPDATE med_indicator_draft SET status='trial_passed',current_version=7,
                  trial_draft_version=6 WHERE draft_id='DRAFT_1'
                """);
        assertThatThrownBy(() -> repository.submit("DRAFT_1", "hospital_001", 7, "user_001"))
                .isInstanceOf(ImplementationException.class)
                .hasMessageContaining("当前版本");
    }

    private void insert(String id, String hospital, String status, int version,
            String trialResult, Integer trialVersion) {
        jdbc.update("""
                INSERT INTO med_indicator_draft
                  (draft_id,hospital_id,proposed_index_code,index_name,index_type,index_desc,stat_cycle,
                   numerator_rule,denominator_rule,filter_rule,exclude_rule,metric_type,
                   metadata_requirements,field_mapping,sql_plan,current_sql,sql_params,sql_id,
                   trial_result,trial_draft_version,status,current_version,generated_by,created_by,
                   updated_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, hospital, "LOCAL_" + id, "测试指标", "本院新增指标", "说明", "month",
                "分子", "分母", "", "", "ratio", "[]", "{}", "{}", "SELECT 1", "{}", "SQL_1",
                trialResult, trialVersion, status, version, "test", "user_001", "user_001");
    }
}

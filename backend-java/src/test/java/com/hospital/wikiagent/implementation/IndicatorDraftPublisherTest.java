package com.hospital.wikiagent.implementation;

import static org.assertj.core.api.Assertions.assertThat;

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

class IndicatorDraftPublisherTest {
    private JdbcTemplate jdbc;
    private IndicatorDraftPublisher publisher;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("publish-draft-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .addScript("classpath:test-runtime-schema.sql").build();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper json = new ObjectMapper();
        IndicatorDraftRepository drafts = new IndicatorDraftRepository(jdbc, transactions, json);
        publisher = new IndicatorDraftPublisher(jdbc, transactions, json, drafts);
        jdbc.update("""
                INSERT INTO med_indicator_draft
                  (draft_id,hospital_id,proposed_index_code,index_name,index_type,index_desc,stat_cycle,
                   numerator_rule,denominator_rule,filter_rule,exclude_rule,metric_type,
                   metadata_requirements,field_mapping,sql_plan,current_sql,sql_params,sql_id,trial_result,
                   trial_draft_version,status,current_version,generated_by,created_by,updated_by,created_at,updated_at)
                VALUES ('DRAFT_PUB','hospital_001','LOCAL_PUB','测试发布指标','本院新增','说明','month',
                  '分子','分母','','','ratio','["patient_id"]',
                  '{"patient_id":{"db_name":"business","table_name":"patient","column_name":"id","data_type":"varchar"}}',
                  '{"main_table":"patient"}','SELECT 1','{}','SQL_1',
                  '{"status":"success","run_id":"RUN_1"}',6,'pending_approval',6,
                  'test','user_001','user_001',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
    }

    @Test
    void publishesMappingAndImmutableVersionThenRestoresAsNewVersion() {
        Map<String, Object> result = publisher.approve("DRAFT_PUB", "hospital_001", 6, "admin_001");
        assertThat(result).containsEntry("status", "published").containsEntry("active_version", 1);
        assertThat(count("med_index_hospital_defined")).isEqualTo(1);
        assertThat(count("med_index_hospital_defined_version")).isEqualTo(1);
        assertThat(count("med_field_mapping")).isEqualTo(1);

        Map<String, Object> restored = publisher.restore("hospital_001", "LOCAL_PUB", 1, "admin_001");
        assertThat(restored).containsEntry("active_version", 2).containsEntry("restored_from_version", 1);
        assertThat(count("med_index_hospital_defined_version")).isEqualTo(2);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}

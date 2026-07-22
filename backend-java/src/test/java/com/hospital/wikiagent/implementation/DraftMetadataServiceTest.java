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

import com.fasterxml.jackson.databind.ObjectMapper;

class DraftMetadataServiceTest {
    private JdbcTemplate jdbc;
    private DraftMetadataService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("metadata-draft-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .addScript("classpath:test-runtime-schema.sql").build();
        jdbc = new JdbcTemplate(dataSource);
        IndicatorDraftRepository drafts = new IndicatorDraftRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        service = new DraftMetadataService(jdbc, drafts);
        jdbc.update("""
                INSERT INTO med_indicator_draft
                  (draft_id,hospital_id,proposed_index_code,index_name,index_type,index_desc,stat_cycle,
                   numerator_rule,denominator_rule,filter_rule,exclude_rule,metric_type,
                   metadata_requirements,field_mapping,sql_plan,sql_params,trial_result,status,current_version,
                   generated_by,created_by,updated_by,created_at,updated_at)
                VALUES ('DRAFT_META','hospital_001','LOCAL_META','测试指标','本院新增','说明','month',
                  '分子','分母','','','ratio','["hospital_id","consult_id"]','{}',
                  '{"main_table":"consult_record"}','{}','{}','metadata_pending',2,
                  'test','user_001','user_001',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """);
        insertColumn("hospital_id"); insertColumn("consult_id");
    }

    @Test
    void suggestsAndConfirmsOnlyCurrentHospitalSnapshotColumns() {
        Map<String, Object> suggestions = service.suggestions("DRAFT_META", "hospital_001");
        assertThat(suggestions).containsEntry("ready_for_confirmation", true);

        Map<String, Map<String, Object>> mappings = Map.of(
                "hospital_id", mapping("hospital_id"), "consult_id", mapping("consult_id"));
        Map<String, Object> confirmed = service.confirm(
                "DRAFT_META", "hospital_001", 2, mappings, "user_001");
        assertThat(confirmed).containsEntry("status", "metadata_ready").containsEntry("current_version", 3);
    }

    private void insertColumn(String column) {
        jdbc.update("""
                INSERT INTO med_metadata_column
                  (hospital_id,db_name,table_name,column_name,data_type,column_type,is_nullable,column_key,
                   column_default,column_comment,sync_batch_id,sync_time)
                VALUES ('hospital_001','business','consult_record',?,'varchar','varchar','YES','','',?,
                  'BATCH_1',CURRENT_TIMESTAMP)
                """, column, column);
    }

    private static Map<String, Object> mapping(String column) {
        return Map.of("db_name", "business", "table_name", "consult_record",
                "column_name", column, "data_type", "varchar");
    }
}

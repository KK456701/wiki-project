package com.hospital.wikiagent.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.dbhub.DatabaseSourceException;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.metadata.MetadataRepository.Snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;

class MetadataSyncServiceTest {
    private JdbcTemplate jdbc;
    private MetadataRepository repository;
    private DbHubProperties properties;
    private HospitalPrincipal principal;

    @BeforeEach
    void setUp() {
        var database = new EmbeddedDatabaseBuilder()
                .setName("metadata_" + System.nanoTime())
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:test-runtime-schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        repository = new MetadataRepository(jdbc, new ObjectMapper());
        properties = new DbHubProperties();
        principal = new HospitalPrincipal(
                "user_001", "doctor", "hospital_001", Set.of(), false, "session_001");
        jdbc.update("INSERT INTO med_field_mapping "
                        + "(hospital_id,rule_id,business_field,db_name,table_name,column_name,data_type,status) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                "hospital_001", "MQSI2025_001", "admission_id", "winex_all_dev",
                "INP_VISIT", "ADMISSION_ID", "varchar", "confirmed");
    }

    @Test
    void synchronizesOnlyMappedColumnsAndPersistsOverview() {
        MetadataCatalogClient catalog = new StubCatalog();
        MetadataSyncService service = new MetadataSyncService(
                catalog, repository, properties, mock(AgentTraceService.class));

        Map<String, Object> result = service.sync(
                principal, "hospital_001", "winex_all_dev", "dbhub");
        Map<String, Object> overview = service.overview(principal, "WiNEX_All_DEV");

        assertThat(result).containsEntry("tableCount", 2).containsEntry("columnCount", 1);
        assertThat(overview).containsEntry("hasSnapshot", true)
                .containsEntry("tableCount", 2).containsEntry("columnCount", 1)
                .containsEntry("sourceRole", "business")
                .containsEntry("sourceId", "winex_all_dev");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM med_metadata_column WHERE hospital_id='hospital_001'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void reportsColumnTypeAndNullabilityChangesDeterministically() {
        Snapshot previous = new Snapshot(List.of(), List.of(Map.of(
                "table_name", "INP_VISIT", "column_name", "ADMISSION_ID",
                "data_type", "varchar", "column_type", "varchar", "is_nullable", "YES")));
        Snapshot current = new Snapshot(List.of(), List.of(Map.of(
                "table_name", "INP_VISIT", "column_name", "ADMISSION_ID",
                "data_type", "bigint", "column_type", "bigint", "is_nullable", "NO")));

        assertThat(MetadataSyncService.diff(previous, current))
                .extracting(item -> item.get("changeType"))
                .containsExactly("column_type_changed", "column_nullable_changed");
    }

    @Test
    void rejectsUnconfiguredDatabase() {
        MetadataSyncService service = new MetadataSyncService(
                new StubCatalog(), repository, properties, mock(AgentTraceService.class));

        assertThatThrownBy(() -> service.overview(principal, "other_database"))
                .isInstanceOfSatisfying(DatabaseSourceException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DB_SOURCE_ROLE_INVALID"));
    }

    @Test
    void acceptsRealDatabaseWithoutFallingBackToBusiness() {
        MetadataSyncService service = new MetadataSyncService(
                new StubCatalog(), repository, properties, mock(AgentTraceService.class));

        Map<String, Object> result = service.overview(principal, "winex_aima");

        assertThat(result)
                .containsEntry("dbName", "winex_aima")
                .containsEntry("sourceRole", "real")
                .containsEntry("sourceId", "winex_aima")
                .containsEntry("hasSnapshot", false);
    }

    @Test
    void rejectsRetiredDatabaseWithStableCode() {
        MetadataSyncService service = new MetadataSyncService(
                new StubCatalog(), repository, properties, mock(AgentTraceService.class));

        assertThatThrownBy(() -> service.overview(principal, "win60_qa_991827"))
                .isInstanceOfSatisfying(DatabaseSourceException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DB_SOURCE_RETIRED"));
    }

    private static final class StubCatalog implements MetadataCatalogClient {
        @Override
        public List<Map<String, Object>> listTables(
                DatabaseRole role, String databaseName, String schemaName) {
            return List.of(
                    Map.of("TABLE_NAME", "INP_VISIT", "TABLE_TYPE", "BASE TABLE"),
                    Map.of("TABLE_NAME", "UNMAPPED_TABLE", "TABLE_TYPE", "BASE TABLE"));
        }

        @Override
        public List<Map<String, Object>> listColumns(
                DatabaseRole role, String databaseName, String schemaName, String tableName) {
            return List.of(Map.of(
                    "TABLE_NAME", tableName, "COLUMN_NAME", "ADMISSION_ID",
                    "DATA_TYPE", "varchar", "COLUMN_TYPE", "varchar",
                    "IS_NULLABLE", "NO"));
        }

        @Override
        public String sourceName(DatabaseRole role) {
            return role.value();
        }
    }
}

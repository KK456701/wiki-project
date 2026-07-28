package com.hospital.wikiagent.agent.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.sqlserver.RealDatabaseSafetyVerifier;

class BusinessMcpSourceExtractionGatewayTest {

    @Test
    void preciseJsonWrapperKeepsLeadingCteAtTopLevelAndAppliesHospitalScope() {
        String sql = """
                -- SELECT in a comment must not be treated as the final query
                ;WITH source_rows AS (
                    SELECT 'SELECT (ignored)' AS memo, hospitalSoid
                    FROM dbo.source_table
                )
                SELECT hospitalSoid, memo FROM source_rows
                """;

        String wrapped = BusinessMcpSourceExtractionGateway.preciseJsonSql(
                sql,
                "[__wiki_profile_source].[hospitalSoid] = 991827");

        assertThat(wrapped)
                .startsWith("-- SELECT in a comment")
                .contains("[__wiki_profile_source] AS (")
                .contains("SELECT hospitalSoid, memo FROM source_rows")
                .contains("FROM [__wiki_profile_source]"
                        + " WHERE [__wiki_profile_source].[hospitalSoid] = 991827"
                        + " FOR JSON PATH, INCLUDE_NULL_VALUES")
                .doesNotContain("FROM (;WITH");
    }

    @Test
    void preciseJsonWrapperStillUsesDerivedTableForOrdinarySelect() {
        String wrapped = BusinessMcpSourceExtractionGateway.preciseJsonSql(
                "SELECT hospitalSoid FROM dbo.source_table",
                "[__wiki_profile_source].[hospitalSoid] = 991827");

        assertThat(wrapped)
                .contains("FROM (SELECT hospitalSoid FROM dbo.source_table)"
                        + " AS [__wiki_profile_source]")
                .contains("WHERE [__wiki_profile_source].[hospitalSoid] = 991827")
                .contains("FOR JSON PATH, INCLUDE_NULL_VALUES");
    }

    @Test
    void hospitalAliasKeepsExactCaseForCaseSensitiveSqlServer() {
        assertThat(BusinessMcpSourceExtractionGateway.hospitalResultAlias(
                "SELECT t.HOSPITAL_SOID AS hospitalSOID FROM sample t"))
                .isEqualTo("hospitalSOID");
        assertThat(BusinessMcpSourceExtractionGateway.hospitalResultAlias(
                "SELECT t.HOSPITAL_SOID AS hospitalSoid FROM sample t"))
                .isEqualTo("hospitalSoid");
    }

    @Test
    void mcpFailureNeverStartsDeleteTransactionOrUsesOldSnapshot() throws Exception {
        DbHubMcpClient dbHub = mock(DbHubMcpClient.class);
        DataSource dataSource = mock(DataSource.class);
        RealDatabaseSafetyVerifier verifier = mock(RealDatabaseSafetyVerifier.class);
        Connection connection = lockedConnection();
        when(dataSource.getConnection()).thenReturn(connection);
        when(dbHub.executeSql(anyString(), anyString()))
                .thenThrow(new DbHubMcpException("offline"));

        BusinessMcpSourceExtractionGateway gateway =
                gateway(dbHub, dataSource, verifier);
        try (SourceExtractionLease lease = gateway.prepare(request(contract()))) {
            assertThat(lease.result().status()).isEqualTo(ExtractionResult.Status.FAILED);
            assertThat(lease.result().errorCode())
                    .isEqualTo("SOURCE_EXTRACTION_MCP_FAILED");
            assertThat(lease.result().message())
                    .contains("真实库尚未清空或替换");
            assertThat(lease.result().allowsDualExecution()).isFalse();
        }

        verify(connection, never()).setAutoCommit(false);
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test
    void unlistedOrThreePartTargetIsRejectedBeforeOpeningWriteConnection() throws Exception {
        DbHubMcpClient dbHub = mock(DbHubMcpClient.class);
        DataSource dataSource = mock(DataSource.class);
        RealDatabaseSafetyVerifier verifier = mock(RealDatabaseSafetyVerifier.class);
        Map<String, Object> invalid = new java.util.LinkedHashMap<>(contract());
        invalid.put("event_table", "winex_aima.dbo.MRAS_BUSINESS_DEATH");
        invalid.put("target_tables", List.of(
                "winex_aima.dbo.MRAS_BUSINESS_DEATH", "MRAS_TARGET_DEFINITION"));

        BusinessMcpSourceExtractionGateway gateway =
                gateway(dbHub, dataSource, verifier);
        try (SourceExtractionLease lease = gateway.prepare(request(invalid))) {
            assertThat(lease.result().status()).isEqualTo(ExtractionResult.Status.FAILED);
            assertThat(lease.result().errorCode()).isEqualTo("EXTRACTION_CONTRACT_INVALID");
        }

        verify(dataSource, never()).getConnection();
    }

    @Test
    void successfulEmptyArraysCreateAnAtomicNoSampleSnapshot() throws Exception {
        DbHubMcpClient dbHub = mock(DbHubMcpClient.class);
        DataSource dataSource = mock(DataSource.class);
        RealDatabaseSafetyVerifier verifier = mock(RealDatabaseSafetyVerifier.class);
        ConnectionFixture fixture = connectionFixture(false);
        when(dataSource.getConnection()).thenReturn(fixture.connection());
        when(dbHub.executeSql(anyString(), anyString()))
                .thenReturn(List.of(Map.of("json_payload", "[]")));

        BusinessMcpSourceExtractionGateway gateway =
                gateway(dbHub, dataSource, verifier);
        SourceExtractionLease lease = gateway.prepare(request(contract()));
        assertThat(lease.result().status())
                .withFailMessage(lease.result().toString())
                .isEqualTo(ExtractionResult.Status.SUCCESS);
        assertThat(lease.result().extractedRows()).isZero();
        assertThat(lease.result().insertedRows()).isZero();
        lease.close();

        verify(fixture.deleteStatement(),
                org.mockito.Mockito.times(28)).executeUpdate();
        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
        verify(fixture.connection()).close();
    }

    @Test
    void insertFailureRollsBackTheWholeTwentyEightTableReplacement() throws Exception {
        DbHubMcpClient dbHub = mock(DbHubMcpClient.class);
        DataSource dataSource = mock(DataSource.class);
        RealDatabaseSafetyVerifier verifier = mock(RealDatabaseSafetyVerifier.class);
        ConnectionFixture fixture = connectionFixture(true);
        when(dataSource.getConnection()).thenReturn(fixture.connection());
        when(dbHub.executeSql(anyString(), anyString()))
                .thenReturn(
                        List.of(Map.of(
                                "json_payload",
                                "[{\"mrasBusinessDeathId\":1}]")),
                        List.of(Map.of("json_payload", "[]")));

        BusinessMcpSourceExtractionGateway gateway =
                gateway(dbHub, dataSource, verifier);
        try (SourceExtractionLease lease = gateway.prepare(request(contract()))) {
            assertThat(lease.result().status()).isEqualTo(ExtractionResult.Status.FAILED);
            assertThat(lease.result().errorCode())
                    .withFailMessage(lease.result().toString())
                    .isEqualTo("REAL_DB_INSERT_FAILED");
            assertThat(lease.result().message()).contains("MRAS_BUSINESS_DEATH");
            assertThat(lease.result().allowsDualExecution()).isFalse();
        }

        verify(fixture.deleteStatement(),
                org.mockito.Mockito.times(28)).executeUpdate();
        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(fixture.connection()).close();
    }

    @Test
    void preciseJsonEnvelopePreservesLargeNumericIdentifiers() throws Exception {
        DbHubMcpClient dbHub = mock(DbHubMcpClient.class);
        DataSource dataSource = mock(DataSource.class);
        RealDatabaseSafetyVerifier verifier = mock(RealDatabaseSafetyVerifier.class);
        ConnectionFixture fixture = connectionFixture(false);
        when(dataSource.getConnection()).thenReturn(fixture.connection());
        when(dbHub.executeSql(anyString(), anyString()))
                .thenReturn(
                        List.of(Map.of(
                                "json_payload",
                                "[{\"mrasBusinessDeathId\":514595020008415200,"
                                        + "\"memo\":\"\"}]")),
                        List.of(Map.of("json_payload", "[]")));

        BusinessMcpSourceExtractionGateway gateway =
                gateway(dbHub, dataSource, verifier);
        try (SourceExtractionLease lease = gateway.prepare(request(contract()))) {
            assertThat(lease.result().status())
                    .withFailMessage(lease.result().toString())
                    .isEqualTo(ExtractionResult.Status.SUCCESS);
        }

        verify(fixture.insertStatement())
                .setObject(1, 514595020008415200L);
    }

    private static Connection lockedConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(0);
        return connection;
    }

    private static ExtractionRequest request(Map<String, Object> contract) {
        String sourceSql = "SELECT 1 AS mrasBusinessDeathId, "
                + "1 AS hospitalSoid WHERE :start_time < :end_time";
        return new ExtractionRequest(
                "TRACE_1", "TASK_1", "hospital_001", "user_1", "KB_TEST",
                "HXZD-009-001", "HXZD-009-001-company-default",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0),
                sourceSql, sha256(sourceSql), Map.of(), "winex_all_dev", "winex_aima",
                "IDEMPOTENCY_1", 1L, contract);
    }

    private static BusinessMcpSourceExtractionGateway gateway(
            DbHubMcpClient dbHub,
            DataSource dataSource,
            RealDatabaseSafetyVerifier verifier) {
        return new BusinessMcpSourceExtractionGateway(
                dbHub,
                new DbHubProperties(),
                new SqlParameterBinder(),
                new ReadOnlySqlValidator(),
                dataSource,
                verifier);
    }

    private static Map<String, Object> contract() {
        return Map.ofEntries(
                Map.entry("database_name", "winex_aima"),
                Map.entry("schema_name", "dbo"),
                Map.entry("route", "EVENT"),
                Map.entry("event_no", "CORE_DEATH"),
                Map.entry("event_table", "MRAS_BUSINESS_DEATH"),
                Map.entry("dependency_tables", List.of("MRAS_TARGET_DEFINITION")),
                Map.entry("target_tables", List.of(
                        "MRAS_BUSINESS_DEATH", "MRAS_TARGET_DEFINITION")),
                Map.entry("allowed_result_fields", Map.of(
                        "MRAS_BUSINESS_DEATH", List.of("MRAS_BUSINESS_DEATH_ID"),
                        "MRAS_TARGET_DEFINITION", List.of("MRAS_TARGET_DEFINITION_ID"))),
                Map.entry("target_schema_fingerprints", Map.of(
                        "MRAS_BUSINESS_DEATH",
                        fingerprint("MRAS_BUSINESS_DEATH_ID"),
                        "MRAS_TARGET_DEFINITION",
                        fingerprint("MRAS_TARGET_DEFINITION_ID"))));
    }

    private static ConnectionFixture connectionFixture(boolean failInsert)
            throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);

        PreparedStatement lock = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        when(lock.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getInt(1)).thenReturn(0);

        PreparedStatement eventColumns =
                columnStatement("MRAS_BUSINESS_DEATH_ID");
        PreparedStatement targetColumns =
                columnStatement("MRAS_TARGET_DEFINITION_ID");
        AtomicInteger columnQuery = new AtomicInteger();
        PreparedStatement delete = mock(PreparedStatement.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        if (failInsert) {
            when(insert.executeBatch()).thenThrow(new SQLException("test insert failure"));
        }
        PreparedStatement release = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("sp_getapplock")) {
                return lock;
            }
            if (sql.contains("FROM sys.columns")) {
                return columnQuery.getAndIncrement() == 0
                        ? eventColumns : targetColumns;
            }
            if (sql.startsWith("DELETE FROM")) {
                return delete;
            }
            if (sql.startsWith("INSERT INTO")) {
                return insert;
            }
            if (sql.contains("sp_releaseapplock")) {
                return release;
            }
            throw new AssertionError("unexpected SQL in test: " + sql);
        });
        return new ConnectionFixture(connection, delete, insert);
    }

    private static PreparedStatement columnStatement(String column) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("column_name")).thenReturn(column);
        when(rows.getInt("column_id")).thenReturn(1);
        when(rows.getString("data_type")).thenReturn("int");
        when(rows.getInt("max_length")).thenReturn(4);
        when(rows.getInt("precision")).thenReturn(10);
        when(rows.getInt("scale")).thenReturn(0);
        when(rows.getBoolean("is_nullable")).thenReturn(false);
        when(rows.getBoolean("has_default")).thenReturn(false);
        when(rows.getBoolean("is_identity")).thenReturn(false);
        return statement;
    }

    private static String fingerprint(String column) {
        String canonical = "[{\"ordinal\":1,\"name\":\"" + column
                + "\",\"type\":\"int\",\"max_length\":4,\"precision\":10,"
                + "\"scale\":0,\"nullable\":false,\"identity\":false,"
                + "\"has_default\":false}]";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ConnectionFixture(
            Connection connection,
            PreparedStatement deleteStatement,
            PreparedStatement insertStatement) {
    }
}

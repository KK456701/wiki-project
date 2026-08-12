package com.hospital.wikiagent.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.QueryTimeoutException;

import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.dbhub.DatabaseSourceException;

class SqlPreviewServiceTest {
    private final EntityPageParser entities = mock(EntityPageParser.class);
    private final JdbcTemplate business = mock(JdbcTemplate.class);
    private final JdbcTemplate real = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JdbcTemplate> businessProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JdbcTemplate> realProvider = mock(ObjectProvider.class);
    private SqlPreviewService service;

    @BeforeEach
    void setUp() {
        when(businessProvider.getIfAvailable()).thenReturn(business);
        when(realProvider.getIfAvailable()).thenReturn(real);
        when(entities.getEntity("HXZD-003-001")).thenReturn(entity());
        service = new SqlPreviewService(new ReadOnlySqlValidator(),
                new MrasSqlLineageAnalyzer(), entities, businessProvider, realProvider);
    }

    @Test
    void normalizesSqlServerNavicatPreambleIntoOneReadOnlyQuery() {
        String result = service.normalize("""
                -- 数据链路可执行脚本
                SET NOCOUNT ON;
                DECLARE @startTime DATETIME2(0) = '2025-01-01 00:00:00';
                DECLARE @endTime DATETIME2(0) = '2026-01-01 00:00:00';
                DECLARE @department NVARCHAR(MAX) = NULL; -- 可选筛选参数，按需填写

                SELECT COUNT_BIG(1) AS total_count
                FROM MRAS_BUSINESS_CONSULTATION
                WHERE REQUESTED_AT >= @startTime AND REQUESTED_AT < @endTime
                  AND (@department IS NULL OR DEPARTMENT_NAME = @department);
                """, DatabaseRole.REAL, "", "");

        assertThat(result).startsWith("-- 数据链路可执行脚本");
        assertThat(result).doesNotContain("DECLARE", "SET NOCOUNT", "@startTime", "@endTime", "@department");
        assertThat(new ReadOnlySqlValidator().validateAdHocReadOnly(result).ok()).isTrue();
    }

    @Test
    void bindsOracleTemplatePeriodAndRejectsTablesOutsideIndicator() {
        String result = service.normalize("""
                SELECT * FROM CONSULTATION
                WHERE REQUESTED_AT >= :startTime AND REQUESTED_AT < :endTime
                """, DatabaseRole.BUSINESS,
                "2025-01-01 00:00:00", "2026-01-01 00:00:00");

        assertThat(result).contains("TIMESTAMP '2025-01-01 00:00:00'")
                .contains("TIMESTAMP '2026-01-01 00:00:00'");
        assertThatThrownBy(() -> service.execute(principal(), new SqlPreviewService.PreviewRequest(
                "SELECT * FROM UNRELATED_PATIENT_TABLE", "BUSINESS", "HXZD-003-001",
                "HXZD-003-001", "2025-01-01 00:00:00", "2026-01-01 00:00:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前指标链路外");
    }

    @Test
    void rejectsTemplatePeriodWhenPageContextHasNoDates() {
        assertThatThrownBy(() -> service.normalize(
                "SELECT * FROM CONSULTATION WHERE REQUESTED_AT BETWEEN :startTime AND :endTime",
                DatabaseRole.BUSINESS, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("统计开始时间");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void capsPreviewAtTwoHundredRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 201; index++) rows.add(Map.of("ROW_NO", index));
        when(real.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenReturn(new SqlPreviewService.QueryData(List.of("ROW_NO"), rows));

        var result = service.execute(principal(), new SqlPreviewService.PreviewRequest(
                "SELECT * FROM MRAS_BUSINESS_CONSULTATION", "REAL", "HXZD-003-001",
                "HXZD-003-001", "", ""));

        assertThat(result.rowCount()).isEqualTo(200);
        assertThat(result.truncated()).isTrue();
        assertThat(result.columns()).containsExactly("ROW_NO");
        assertThat(result.rows()).hasSize(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void routesOracleSqlToBusinessTemplateAndKeepsColumnMetadataForEmptyResults() {
        when(business.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenReturn(new SqlPreviewService.QueryData(List.of("TOTAL_COUNT"), List.of()));

        var result = service.execute(principal(), new SqlPreviewService.PreviewRequest(
                "SELECT COUNT(*) AS TOTAL_COUNT FROM CONSULTATION", "BUSINESS", "HXZD-003-001",
                "HXZD-003-001", "", ""));

        assertThat(result.databaseRole()).isEqualTo("BUSINESS");
        assertThat(result.columns()).containsExactly("TOTAL_COUNT");
        assertThat(result.rowCount()).isZero();
        verify(business).query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsThirtySecondQueryTimeoutToStablePreviewError() {
        when(real.query(anyString(), any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.ResultSetExtractor.class)))
                .thenThrow(new QueryTimeoutException("query timed out"));

        assertThatThrownBy(() -> service.execute(principal(), new SqlPreviewService.PreviewRequest(
                "SELECT * FROM MRAS_BUSINESS_CONSULTATION", "REAL", "HXZD-003-001",
                "HXZD-003-001", "", "")))
                .isInstanceOf(DatabaseSourceException.class)
                .satisfies(error -> assertThat(((DatabaseSourceException) error).code())
                        .isEqualTo("SQL_PREVIEW_EXECUTION_FAILED"));
    }

    @Test
    void rejectsUnknownIndicatorInsideAuthenticatedHospitalContext() {
        HospitalPrincipal otherHospital = new HospitalPrincipal(
                "user", "account", "hospital_002", Set.of(), false, "session");

        assertThatThrownBy(() -> service.execute(otherHospital, new SqlPreviewService.PreviewRequest(
                "SELECT * FROM MRAS_BUSINESS_CONSULTATION", "REAL", "HXZD-999-999",
                "HXZD-999-999", "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前指标口径不存在");
        verify(entities).getEntity("HXZD-999-999");
    }

    private static HospitalPrincipal principal() {
        return new HospitalPrincipal("user", "account", "hospital_001", Set.of(), false, "session");
    }

    private static EntityPageData entity() {
        return new EntityPageData(
                "HXZD-003-001", "急会诊及时到位率", "", "HXZD-003-001", "推荐方案",
                "", "", "", "", "", "", "", "", "",
                "SELECT * FROM CONSULTATION WHERE REQUESTED_AT >= :startTime AND REQUESTED_AT < :endTime",
                "SELECT * FROM MRAS_BUSINESS_CONSULTATION",
                "SELECT DEPT_NAME FROM MRAS_BUSINESS_CONSULTATION",
                "SELECT * FROM MRAS_BUSINESS_CONSULTATION",
                "", "MRAS_BUSINESS_CONSULTATION", List.of("CONSULTATION"), List.of());
    }
}

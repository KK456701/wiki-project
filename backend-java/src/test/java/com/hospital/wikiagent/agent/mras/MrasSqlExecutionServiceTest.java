package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.service.SyncDataService;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;

/**
 * MrasSqlExecutionService 单元测试：验证知识库查询的端到端编排逻辑。
 */
class MrasSqlExecutionServiceTest {

    private EntityPageParser entityPageParser;
    private IndicatorDatabaseQueryClient databaseQuery;
    private SyncDataService syncDataService;
    private MrasSqlExecutionService service;

    @BeforeEach
    void setUp() {
        entityPageParser = new EntityPageParser();
        databaseQuery = mock(IndicatorDatabaseQueryClient.class);
        SqlServerProperties props = new SqlServerProperties();
        props.setHospitalSoid(991827L);
        // 多口径表名替换绑定抽取成功，因此这里必须提供可用的 SyncDataService，
        // 否则抽取被判为「跳过」，多口径查询会按设计直接报错而不做表名替换。
        syncDataService = mock(SyncDataService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncDataService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(syncDataService);
        service = new MrasSqlExecutionService(
                entityPageParser,
                new ConceptPageParser(),
                new MrasTemplateRenderer(),
                new MrasParameterMapper(),
                new ReadOnlySqlValidator(),
                new SqlParameterBinder(),
                databaseQuery,
                provider,
                props);
    }

    @Test
    void supportsReturnsTrueForKnownIndicator() {
        assertThat(service.supports("HXZD-001-001")).isTrue();
    }

    @Test
    void supportsReturnsFalseForUnknownIndicator() {
        assertThat(service.supports("HXZD-999-999")).isFalse();
    }

    @Test
    void executeOverviewFailsForUnknownIndicator() {
        ToolResult result = service.executeOverview(
                "HXZD-999-999",
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59),
                null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("MRAS_ENTITY_NOT_FOUND");
    }

    @Test
    void executeOverviewSucceedsWithMockedDatabase() {
        when(databaseQuery.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of(Map.of(
                        "分子入院48小时内转科患者人次数", 5L,
                        "分母同期入院患者总人次数", 100L,
                        "监测情况", 0.05,
                        "目标值", 0.10,
                        "是否达标", "是")));

        ToolResult result = service.executeOverview(
                "HXZD-001-001",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59),
                null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("MRAS_QUERY_COMPLETED");
        assertThat(result.data().get("indicatorCode")).isEqualTo("HXZD-001-001");
        assertThat(result.data().get("sqlSource")).isEqualTo("mras");
        assertThat(result.data().get("numeratorCount")).isEqualTo(5L);
        assertThat(result.data().get("denominatorCount")).isEqualTo(100L);
        assertThat(result.data().get("noSample")).isEqualTo(false);
        // 百分比类指标：概览 SQL 返回的 0-1 比值必须换算为百分数
        assertThat(((Number) result.data().get("resultValue")).doubleValue()).isEqualTo(5.0);
        assertThat(((Number) result.data().get("targetValue")).doubleValue()).isEqualTo(10.0);
        assertThat(result.data().get("unit")).isEqualTo("percentage");
    }

    @Test
    void executeOverviewHandlesEmptyResult() {
        when(databaseQuery.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of());

        ToolResult result = service.executeOverview(
                "HXZD-001-001",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59),
                null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("status")).isEqualTo("empty");
        assertThat(result.data().get("noSample")).isEqualTo(true);
    }

    @Test
    void executeDeptStatFailsWhenSqlEmpty() {
        // HXZD-006-003 的 001 文件有 deptStatSql，但先测试一个没有的
        ToolResult result = service.executeDeptStat(
                "HXZD-999-999",
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59),
                null);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("MRAS_ENTITY_NOT_FOUND");
    }

    @Test
    void getExplanationContextReturnsMetadata() {
        Map<String, String> context = service.getExplanationContext("HXZD-001-001");

        assertThat(context.get("indicator_code")).isEqualTo("HXZD-001-001");
        assertThat(context.get("indicator_name")).contains("患者入院48小时内转科的比例");
        assertThat(context.get("definition")).contains("入院48小时内转科");
        assertThat(context.get("formula")).contains("分子");
        assertThat(context.get("caliber")).isNotBlank();
        assertThat(context.get("monitor_params")).contains("时间维度");
    }

    @Test
    void parseOverviewAppliesDirectionAndRatioUnit() {
        when(databaseQuery.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of(Map.of("监测情况", 0.8, "目标值", 1.0)));

        // 逐步降低类指标：达标方向透传为 "<"，百分比仍 ×100
        ToolResult lower = service.executeOverview(
                "HXZD-011-001",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59),
                null, null);
        assertThat(lower.ok()).isTrue();
        assertThat(lower.data().get("targetDirection")).isEqualTo("<");
        assertThat(lower.data().get("unit")).isEqualTo("percentage");
        assertThat(((Number) lower.data().get("resultValue")).doubleValue()).isEqualTo(80.0);

        // 比值类指标（计量单位“比值”）：单位为 ratio，数值不 ×100，无导向
        ToolResult ratio = service.executeOverview(
                "HXZD-012-002",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59),
                null, null);
        assertThat(ratio.ok()).isTrue();
        assertThat(ratio.data().get("unit")).isEqualTo("ratio");
        assertThat(((Number) ratio.data().get("resultValue")).doubleValue()).isEqualTo(0.8);
        assertThat(ratio.data().get("targetDirection")).isNull();
    }

    @Test
    void getExplanationContextEmptyForUnknown() {
        Map<String, String> context = service.getExplanationContext("HXZD-999-999");
        assertThat(context).isEmpty();
    }

    @Test
    void multiCaliberIndicatorReplacesTableNameInSql() {
        // HXZD-015-001 有两个变体（_001 和 _002），是多口径指标
        java.util.List<EntityPageData> variants = entityPageParser.getVariants("HXZD-015-001");
        assertThat(variants).hasSizeGreaterThanOrEqualTo(2);

        when(databaseQuery.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of(Map.of("分子", 10, "分母", 100)));

        // 口径1：HXZD-015-001_001 → SQL 应查 MRAS_BUSINESS_ANTI_1
        ToolResult result1 = service.executeOverview("HXZD-015-001", "HXZD-015-001_001",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59), null, null);
        assertThat(result1.ok()).isTrue();

        ArgumentCaptor<String> sqlCaptor1 = ArgumentCaptor.forClass(String.class);
        verify(databaseQuery).execute(eq(DatabaseRole.REAL), sqlCaptor1.capture());
        assertThat(sqlCaptor1.getValue()).contains("MRAS_BUSINESS_ANTI_1");

        // 口径2：HXZD-015-001_002 → SQL 应查 MRAS_BUSINESS_ANTI_2
        ToolResult result2 = service.executeOverview("HXZD-015-001", "HXZD-015-001_002",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59), null, null);
        assertThat(result2.ok()).isTrue();

        ArgumentCaptor<String> sqlCaptor2 = ArgumentCaptor.forClass(String.class);
        verify(databaseQuery, org.mockito.Mockito.times(2)).execute(eq(DatabaseRole.REAL), sqlCaptor2.capture());
        assertThat(sqlCaptor2.getAllValues().get(1)).contains("MRAS_BUSINESS_ANTI_2");
    }

    @Test
    void multiCaliberExtractionFailureReportsReasonWithoutFallback() {
        // 抽取失败（如源表 SQL 命名参数未绑定）时口径表是空表，
        // 必须带原因硬报错，既不返回 0/0，也不回退查原表。
        org.mockito.Mockito.doThrow(new RuntimeException("Named parameter not bound : exDeptSet"))
                .when(syncDataService).syncEventData(org.mockito.ArgumentMatchers.any());

        ToolResult result = service.executeOverview("HXZD-015-001", "HXZD-015-001_001",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59), null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("MRAS_CALIBER_EXTRACTION_FAILED");
        assertThat(result.summary()).contains("exDeptSet");
        // 不回退：一条 SQL 都不该发给数据库
        org.mockito.Mockito.verifyNoInteractions(databaseQuery);
    }

    @Test
    void singleCaliberIndicatorDoesNotReplaceTableName() {
        // HXZD-001-001 只有一个变体（或无变体），是单口径指标
        when(databaseQuery.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of(Map.of("分子", 5, "分母", 50)));

        service.executeOverview("HXZD-001-001", (String) null,
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59), null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(databaseQuery).execute(eq(DatabaseRole.REAL), sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        // 单口径：SQL 不应该有 _1 或 _2 后缀
        assertThat(sql).doesNotContain("_1");
        assertThat(sql).doesNotContain("_2");
    }
}

package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.service.SyncDataService;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;

/**
 * MrasSqlExecutionService 单元测试：验证领导知识库查询的端到端编排逻辑。
 */
class MrasSqlExecutionServiceTest {

    private EntityPageParser entityPageParser;
    private IndicatorDatabaseQueryClient databaseQuery;
    private MrasSqlExecutionService service;

    @BeforeEach
    void setUp() {
        entityPageParser = new EntityPageParser();
        databaseQuery = mock(IndicatorDatabaseQueryClient.class);
        SqlServerProperties props = new SqlServerProperties();
        props.setHospitalSoid(991827L);
        @SuppressWarnings("unchecked")
        ObjectProvider<SyncDataService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
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
        assertThat(result.data().get("indicator_code")).isEqualTo("HXZD-001-001");
        assertThat(result.data().get("sql_source")).isEqualTo("mras");
        assertThat(result.data().get("numerator_count")).isEqualTo(5L);
        assertThat(result.data().get("denominator_count")).isEqualTo(100L);
        assertThat(result.data().get("no_sample")).isEqualTo(false);
        // 百分比类指标：概览 SQL 返回的 0-1 比值必须换算为百分数
        assertThat(((Number) result.data().get("result_value")).doubleValue()).isEqualTo(5.0);
        assertThat(((Number) result.data().get("target_value")).doubleValue()).isEqualTo(10.0);
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
        assertThat(result.data().get("no_sample")).isEqualTo(true);
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
        assertThat(lower.data().get("target_direction")).isEqualTo("<");
        assertThat(lower.data().get("unit")).isEqualTo("percentage");
        assertThat(((Number) lower.data().get("result_value")).doubleValue()).isEqualTo(80.0);

        // 比值类指标（计量单位“比值”）：单位为 ratio，数值不 ×100，无导向
        ToolResult ratio = service.executeOverview(
                "HXZD-012-002",
                LocalDateTime.of(2025, 3, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59),
                null, null);
        assertThat(ratio.ok()).isTrue();
        assertThat(ratio.data().get("unit")).isEqualTo("ratio");
        assertThat(((Number) ratio.data().get("result_value")).doubleValue()).isEqualTo(0.8);
        assertThat(ratio.data().get("target_direction")).isNull();
    }

    @Test
    void getExplanationContextEmptyForUnknown() {
        Map<String, String> context = service.getExplanationContext("HXZD-999-999");
        assertThat(context).isEmpty();
    }
}

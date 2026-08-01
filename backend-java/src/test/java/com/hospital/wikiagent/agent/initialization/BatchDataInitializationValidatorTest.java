package com.hospital.wikiagent.agent.initialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.initialization.BatchDataInitializationValidator.ValidationTarget;
import com.hospital.wikiagent.agent.initialization.InitializationValidationReport.Decision;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.IndicatorDataFlowTypeResolver;
import com.hospital.wikiagent.agent.mras.MrasParameterMapper;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.metadata.MetadataCatalogClient;

class BatchDataInitializationValidatorTest {
    private EntityPageParser entities;
    private MetadataCatalogClient metadata;
    private IndicatorDatabaseQueryClient query;
    private KnowledgeDataDictionary dictionary;
    private BatchDataInitializationValidator validator;

    @BeforeEach
    void setUp() {
        entities = mock(EntityPageParser.class);
        metadata = mock(MetadataCatalogClient.class);
        query = mock(IndicatorDatabaseQueryClient.class);
        dictionary = mock(KnowledgeDataDictionary.class);
        when(dictionary.sourceSystem(anyString())).thenReturn("测试系统");
        when(dictionary.fieldLabel(anyString(), anyString())).thenReturn("测试字段");
        when(entities.getEntity(anyString())).thenReturn(entity());

        DbHubProperties properties = new DbHubProperties();
        MrasSqlLineageAnalyzer analyzer = new MrasSqlLineageAnalyzer();
        validator = new BatchDataInitializationValidator(
                entities, analyzer, new MrasTemplateRenderer(),
                new MrasParameterMapper(), new ReadOnlySqlValidator(),
                new SqlParameterBinder(), metadata, query, properties, dictionary,
                new IndicatorDataFlowTypeResolver(analyzer));
    }

    @Test
    void runnableWhenBothSchemasAndWindowDataAreAvailable() {
        stubCatalog(true);
        when(query.execute(any(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(1);
            if (sql.contains("init_source")) return List.of(Map.of("row_count", 10L));
            if (sql.contains("[TARGET]")) return List.of(Map.of("total_count", 10L));
            return List.of(Map.of("total_count", 10L, "null_0", 0L, "null_1", 0L));
        });

        InitializationValidationReport report = validate();

        assertThat(report.profiles()).hasSize(1);
        assertThat(report.profiles().get(0).decision()).isEqualTo(Decision.RUNNABLE);
        assertThat(report.profiles().get(0).businessSourceCount()).isEqualTo(10L);
        assertThat(report.toTraceOutput()).containsEntry("reused", false);
    }

    @Test
    void missingSourceColumnBlocksOnlyThatProfile() {
        stubCatalog(false);
        when(query.execute(any(), anyString())).thenReturn(List.of(Map.of("total_count", 10L)));

        InitializationValidationReport report = validate();

        assertThat(report.profiles().get(0).decision()).isEqualTo(Decision.BLOCKED);
        assertThat(report.items()).anyMatch(item ->
                "INIT_MISSING_COLUMN".equals(item.errorCode())
                        && item.databaseRole() == DatabaseRole.BUSINESS);
    }

    @Test
    void directRealQueryDoesNotRequireTargetTableOrBusinessSourceSql() {
        when(entities.getEntity(anyString())).thenReturn(entityWithTarget(""));
        stubCatalog(true);
        when(query.execute(any(), anyString())).thenReturn(List.of(Map.of("total_count", 10L)));

        InitializationValidationReport report = validate();

        assertThat(report.profiles().get(0).decision()).isEqualTo(Decision.RUNNABLE);
        assertThat(report.items()).anyMatch(item ->
                "INIT_UPSTREAM_SYNC_NOT_REGISTERED".equals(item.errorCode())
                        && item.databaseRole() == DatabaseRole.REAL
                        && item.message().contains("上游同步"));
    }

    @Test
    void incompleteProfileProducesOneExplicitSkippedRecord() {
        when(entities.getEntity(anyString())).thenReturn(entityWithoutSql());

        InitializationValidationReport report = validate();

        assertThat(report.profiles().get(0).decision()).isEqualTo(Decision.SKIPPED);
        assertThat(report.items()).singleElement().satisfies(item -> {
            assertThat(item.errorCode()).isEqualTo("PROFILE_NOT_IMPLEMENTED");
            assertThat(item.action()).isEqualTo("跳过");
        });
    }

    @Test
    void realSnapshotZeroRowsFailsWhenBusinessSourceHasRows() {
        when(query.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of(Map.of("total_count", 0L)));

        var result = validator.validateRealSnapshot(
                target(), 10L, start(), end());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("REAL_EXTRACTION_EMPTY");
    }

    @Test
    void realSnapshotAllowsMissingOptionalChineseFieldLabel() {
        when(dictionary.fieldLabel(anyString(), anyString())).thenReturn(null);
        when(metadata.listColumns(eq(DatabaseRole.REAL), anyString(), anyString(), anyString()))
                .thenReturn(List.of(Map.of("COLUMN_NAME", "EVENT_AT")));
        when(query.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of(Map.of("total_count", 10L, "null_0", 2L)));

        var result = validator.validateRealSnapshot(
                target(), 10L, start(), end());

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).containsEntry("qualityStatus", "WARNING");
        assertThat(result.output().get("nullRates")).asList().hasSize(1);
    }

    @Test
    void realSnapshotTreatsNullSumFromEmptyTableAsZero() {
        when(metadata.listColumns(eq(DatabaseRole.REAL), anyString(), anyString(), anyString()))
                .thenReturn(List.of(Map.of("COLUMN_NAME", "EVENT_AT")));
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("total_count", 0L);
        aggregate.put("null_0", null);
        when(query.execute(eq(DatabaseRole.REAL), anyString()))
                .thenReturn(List.of(aggregate));

        var result = validator.validateRealSnapshot(
                target(), 0L, start(), end());

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).containsEntry("realRowCount", 0L);
        assertThat(result.output().get("nullRates")).asList().isEmpty();
    }

    @Test
    void targetOutputDiscoveryIgnoresAliasesInsideDerivedQueries() {
        String selectList = BatchDataInitializationValidator.topLevelSelectList("""
                SELECT x.ID AS bizId, x.GRADE AS surgLevelCode
                FROM (
                    SELECT s.ID, s.RAW_GRADE AS SURGERY_GRADE_CODE
                    FROM SRC s
                ) x
                """);

        assertThat(selectList)
                .contains("AS bizId", "AS surgLevelCode")
                .doesNotContain("SURGERY_GRADE_CODE");
    }

    private InitializationValidationReport validate() {
        return validator.validate(
                "BJOB_1", "hospital_1", List.of(target()), start(), end(),
                "2025-01-01 00:00:00", "2026-01-01 00:00:00");
    }

    private void stubCatalog(boolean includeSourceEventAt) {
        when(metadata.listTables(any(), anyString(), anyString())).thenAnswer(invocation -> {
            DatabaseRole role = invocation.getArgument(0);
            return role == DatabaseRole.BUSINESS
                    ? List.of(Map.of("TABLE_NAME", "SRC"))
                    : List.of(Map.of("TABLE_NAME", "TARGET"));
        });
        when(metadata.listColumns(any(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            DatabaseRole role = invocation.getArgument(0);
            String table = invocation.getArgument(3);
            if (role == DatabaseRole.REAL) {
                return "TARGET".equalsIgnoreCase(table)
                        ? List.of(Map.of("TABLE_NAME", "TARGET", "COLUMN_NAME", "EVENT_AT"))
                        : List.of();
            }
            if (!"SRC".equalsIgnoreCase(table)) return List.of();
            return includeSourceEventAt
                    ? List.of(
                            Map.of("TABLE_NAME", "SRC", "COLUMN_NAME", "ID"),
                            Map.of("TABLE_NAME", "SRC", "COLUMN_NAME", "EVENT_AT"))
                    : List.of(Map.of("TABLE_NAME", "SRC", "COLUMN_NAME", "ID"));
        });
    }

    private static EntityPageData entity() {
        return entityWithTarget("TARGET");
    }

    private static EntityPageData entityWithTarget(String targetTable) {
        return new EntityPageData(
                "HXZD-TEST", "测试指标", "事件时间", "HXZD-TEST", "推荐方案（公版）",
                "", "", "", "", "", "", "", "", "",
                "SELECT s.ID AS id, s.EVENT_AT AS eventAt FROM SRC s "
                        + "WHERE s.EVENT_AT BETWEEN :startTime AND :endTime",
                "SELECT COUNT(1) FROM TARGET t "
                        + "WHERE t.EVENT_AT BETWEEN :marptBeginAt AND :marptEndAt",
                "", "", "CORE_TEST", targetTable, List.of(), List.of());
    }

    private static EntityPageData entityWithoutSql() {
        return new EntityPageData(
                "HXZD-TEST", "测试指标", "", "HXZD-TEST", "可选方案（未实现）",
                "", "", "", "", "", "", "", "", "",
                "", "", "", "", "CORE_TEST", "", List.of(), List.of());
    }

    private static ValidationTarget target() {
        return new ValidationTarget(
                "HXZD-TEST", "测试指标", "HXZD-TEST", "推荐方案（公版）");
    }

    private static LocalDateTime start() {
        return LocalDateTime.of(2025, 1, 1, 0, 0);
    }

    private static LocalDateTime end() {
        return LocalDateTime.of(2026, 1, 1, 0, 0);
    }
}

package com.hospital.wikiagent.agent.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.MrasParameterMapper;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.service.SyncDataService;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;

class DiagnosisEventGateServiceTest {
    @Test
    void blocksWhenMultipleEventsForSameTargetAreEnabled() {
        EntityPageParser entities = mock(EntityPageParser.class);
        SyncDataService sync = mock(SyncDataService.class);
        MrasSqlExecutionService mras = mock(MrasSqlExecutionService.class);
        EntityPageData current = entity("HXZD-008-001", "HXZD-008-001", "CORE_OP_DISC");
        EntityPageData sibling = entity("HXZD-008-001", "HXZD-008-001_002", "CORE_OP_DISC_V2");
        when(entities.getEntity("HXZD-008-001", "H1")).thenReturn(current);
        Map<String, EntityPageData> all = new LinkedHashMap<>();
        all.put(current.variantCode(), current);
        all.put(sibling.variantCode(), sibling);
        when(entities.getAllEntities("H1")).thenReturn(all);
        when(sync.multiCaliberEnableJudge(any())).thenReturn(List.of("CORE_OP_DISC", "CORE_OP_DISC_V2"));
        when(mras.executeOverview(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(ToolResult.success("MRAS_OVERVIEW_EXECUTED", "完成", Map.of(
                        "numeratorCount", 2L, "denominatorCount", 10L, "noSample", false)));
        SqlServerProperties properties = new SqlServerProperties();
        properties.setHospitalSoid(991827L);
        DiagnosisEventGateService service = new DiagnosisEventGateService(
                entities, sync, properties, mras, validator(), parameters(), renderer());

        Map<String, Object> result = service.run("H1", "HXZD-008-001", "HXZD-008-001",
                LocalDateTime.parse("2025-01-01T00:00:00"),
                LocalDateTime.parse("2026-01-01T00:00:00"));

        assertThat(result).containsEntry("status", "BLOCKED")
                .containsEntry("errorCode", "MULTIPLE_CALIBER_EVENTS_ENABLED")
                .containsEntry("repairSuggestion", "请人工检查是否有重复事件启用。");
        assertThat(((Map<?, ?>) result.get("facts")).get("candidateEvents"))
                .isEqualTo(List.of("CORE_OP_DISC", "CORE_OP_DISC_V2"));
        verify(mras).executeOverview(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void recalculatesEveryTimeWithOneEnabledEvent() {
        EntityPageParser entities = mock(EntityPageParser.class);
        SyncDataService sync = mock(SyncDataService.class);
        MrasSqlExecutionService mras = mock(MrasSqlExecutionService.class);
        EntityPageData current = entity("HXZD-001-001", "HXZD-001-001", "CORE_FDR");
        when(entities.getEntity("HXZD-001-001", "H1")).thenReturn(current);
        when(entities.getAllEntities("H1")).thenReturn(Map.of(current.variantCode(), current));
        when(sync.multiCaliberEnableJudge(any())).thenReturn(List.of("CORE_FDR"));
        when(mras.executeOverview(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(ToolResult.success("MRAS_OVERVIEW_EXECUTED", "完成", Map.of(
                        "resultValue", 2.4, "numeratorCount", 10L,
                        "denominatorCount", 417L, "targetValue", 2.0,
                        "targetDirection", ">=", "unit", "%", "noSample", false)));
        SqlServerProperties properties = new SqlServerProperties();
        properties.setHospitalSoid(991827L);
        DiagnosisEventGateService service = new DiagnosisEventGateService(
                entities, sync, properties, mras, validator(), parameters(), renderer());

        LocalDateTime start = LocalDateTime.parse("2025-01-01T00:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-01-01T00:00:00");
        Map<String, Object> first = service.run(
                "H1", "HXZD-001-001", "HXZD-001-001", start, end);
        Map<String, Object> second = service.run(
                "H1", "HXZD-001-001", "HXZD-001-001", start, end);

        assertThat(first).containsEntry("status", "PASSED");
        assertThat(((Map<?, ?>) ((Map<?, ?>) first.get("facts")).get("executionEvidence"))
                .get("denominatorCount")).isEqualTo(417L);
        assertThat(((Map<?, ?>) ((Map<?, ?>) first.get("facts")).get("executionEvidence"))
                .get("attainmentLabel")).isEqualTo("达标");
        assertThat(((Map<?, ?>) ((Map<?, ?>) first.get("facts")).get("executionEvidence"))
                .get("targetValue")).isEqualTo(2.0);
        assertThat(((Map<?, ?>) ((Map<?, ?>) first.get("facts")).get("executionEvidence"))
                .get("evidenceSource")).isEqualTo("FRESH_DIAGNOSIS_CALCULATION");
        String firstId = String.valueOf(((Map<?, ?>) ((Map<?, ?>) first.get("facts"))
                .get("executionEvidence")).get("calculationId"));
        String secondId = String.valueOf(((Map<?, ?>) ((Map<?, ?>) second.get("facts"))
                .get("executionEvidence")).get("calculationId"));
        assertThat(firstId).isNotEqualTo(secondId);
        verify(mras, times(2)).executeOverview(
                "HXZD-001-001", "HXZD-001-001", "H1", start, end, null, null);
    }

    @Test
    void derivesChineseAttainmentForLowerTargetAndMissingTarget() {
        assertThat(DiagnosisEventGateService.attainmentLabel(Map.of(
                "resultValue", 4, "targetValue", 5, "targetDirection", "<=")))
                .isEqualTo("达标");
        assertThat(DiagnosisEventGateService.attainmentLabel(Map.of(
                "resultValue", 6, "targetValue", 5, "targetDirection", "down")))
                .isEqualTo("未达标");
        assertThat(DiagnosisEventGateService.attainmentLabel(Map.of("resultValue", 6)))
                .isEqualTo("待判定");
    }

    @Test
    void preservesFreshCalculationFailureCode() {
        EntityPageParser entities = mock(EntityPageParser.class);
        SyncDataService sync = mock(SyncDataService.class);
        MrasSqlExecutionService mras = mock(MrasSqlExecutionService.class);
        EntityPageData current = entity("HXZD-001-001", "HXZD-001-001", "CORE_FDR");
        when(entities.getEntity("HXZD-001-001", "H1")).thenReturn(current);
        when(entities.getAllEntities("H1")).thenReturn(Map.of(current.variantCode(), current));
        when(sync.multiCaliberEnableJudge(any())).thenReturn(List.of("CORE_FDR"));
        when(mras.executeOverview(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(ToolResult.failure(
                        "failed", "SYNC_QUERY_FAILED", "业务源查询失败", false));
        SqlServerProperties properties = new SqlServerProperties();
        properties.setHospitalSoid(991827L);
        DiagnosisEventGateService service = new DiagnosisEventGateService(
                entities, sync, properties, mras, validator(), parameters(), renderer());

        Map<String, Object> result = service.run("H1", "HXZD-001-001", "HXZD-001-001",
                LocalDateTime.parse("2025-01-01T00:00:00"),
                LocalDateTime.parse("2026-01-01T00:00:00"));

        assertThat(result).containsEntry("status", "BLOCKED")
                .containsEntry("errorCode", "SYNC_QUERY_FAILED")
                .containsEntry("repairSuggestion", "请人工检查是否有重复事件启用。");
        assertThat(((Map<?, ?>) ((Map<?, ?>) result.get("facts")).get("executionEvidence"))
                .get("status")).isEqualTo("FAILED");
    }

    @Test
    void acceptsQuotedKnowledgeSourceSqlAfterTemplateRendering() {
        EntityPageParser entities = mock(EntityPageParser.class);
        SyncDataService sync = mock(SyncDataService.class);
        MrasSqlExecutionService mras = mock(MrasSqlExecutionService.class);
        EntityPageData current = new EntityPageData(
                "HXZD-001-001", "指标", "", "HXZD-001-001", "方案", "定义", "公式", "口径",
                "来源", "", "", "%", "", "",
                "\"SELECT encounterId FROM SOURCE_EVENT WHERE EVENT_AT >= :startTime "
                        + "AND EVENT_AT < :endTime #EQUALS{:syncType; outHosp; AND 1=1}\"",
                "SELECT 1", "", "SELECT ENCOUNTER_ID FROM TARGET", "CORE_FDR", "TARGET", List.of(), List.of());
        when(entities.getEntity("HXZD-001-001", "H1")).thenReturn(current);
        when(entities.getAllEntities("H1")).thenReturn(Map.of(current.variantCode(), current));
        when(sync.multiCaliberEnableJudge(any())).thenReturn(List.of("CORE_FDR"));
        when(mras.executeOverview(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(ToolResult.success("MRAS_OVERVIEW_EXECUTED", "完成", Map.of(
                        "numeratorCount", 2L, "denominatorCount", 10L, "noSample", false)));
        SqlServerProperties properties = new SqlServerProperties();
        properties.setHospitalSoid(991827L);
        DiagnosisEventGateService service = new DiagnosisEventGateService(
                entities, sync, properties, mras, new ReadOnlySqlValidator(),
                new MrasParameterMapper(), new MrasTemplateRenderer());

        Map<String, Object> result = service.run("H1", "HXZD-001-001", "HXZD-001-001",
                LocalDateTime.parse("2025-01-01T00:00:00"),
                LocalDateTime.parse("2026-01-01T00:00:00"));

        assertThat(result).containsEntry("status", "PASSED");
        Map<?, ?> validation = (Map<?, ?>) ((Map<?, ?>) result.get("facts"))
                .get("sourceSqlValidation");
        assertThat(validation.get("ok")).isEqualTo(true);
    }

    private static EntityPageData entity(String code, String variant, String event) {
        return new EntityPageData(code, "指标", "", variant, "方案", "定义", "公式", "口径",
                "来源", "", "", "%", "", "", "SELECT 1 AS encounterId", "SELECT 1",
                "", "SELECT ENCOUNTER_ID FROM TARGET", event, "TARGET", List.of(), List.of());
    }

    private static ReadOnlySqlValidator validator() {
        ReadOnlySqlValidator validator = mock(ReadOnlySqlValidator.class);
        when(validator.validateReadOnly(any())).thenReturn(new ReadOnlySqlValidator.ValidationResult(true, "通过"));
        return validator;
    }

    private static MrasParameterMapper parameters() {
        MrasParameterMapper value = mock(MrasParameterMapper.class);
        when(value.mapTimeOnly(any(), any())).thenReturn(Map.of());
        return value;
    }

    private static MrasTemplateRenderer renderer() {
        MrasTemplateRenderer value = mock(MrasTemplateRenderer.class);
        when(value.renderTemplate(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        return value;
    }
}

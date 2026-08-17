package com.hospital.wikiagent.agent.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.EntitySqlDialectResolver;
import com.hospital.wikiagent.agent.mras.MrasDetailKind;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor;
import com.hospital.wikiagent.agent.mras.MrasParameterMapper;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelInfo;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.service.SyncDataService;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.batch.DetailResultCache;
import com.hospital.wikiagent.agent.initialization.KnowledgeDataDictionary;
import com.hospital.wikiagent.agent.mras.KnowledgeIndexResources;

class DiagnosisCaseEvidenceServiceTest {
    @Test
    void mapsEncounterIdentifierAcrossSourceAliasAndRealTarget() {
        EntityPageParser entities = mock(EntityPageParser.class);
        MrasParameterMapper parameters = mock(MrasParameterMapper.class);
        MrasTemplateRenderer renderer = new MrasTemplateRenderer();
        IndicatorDatabaseQueryClient query = mock(IndicatorDatabaseQueryClient.class);
        MrasDetailSqlExtractor detailExtractor = mock(MrasDetailSqlExtractor.class);
        MrasSqlExecutionService mrasExecution = mock(MrasSqlExecutionService.class);
        EntityPageData entity = new EntityPageData(
                "HXZD-001-001", "转科比例", "", "HXZD-001-001", "推荐方案（公版）",
                "定义", "公式", "口径", "来源", "", "", "%", "", "",
                "SELECT t.ENCOUNTER_ID AS bizId, t.ENCOUNTER_ID AS encounterId FROM T t "
                        + "WHERE t.OUT_AT BETWEEN :startTime AND :endTime",
                "SELECT COUNT(1) FROM MRAS_BUSINESS_FIRSTVISIT event",
                "", "SELECT event.ENCOUNTER_ID FROM MRAS_BUSINESS_FIRSTVISIT event",
                "CORE_FDR", "MRAS_BUSINESS_FIRSTVISIT", List.of(), List.of());
        when(entities.getEntity("HXZD-001-001", "H1")).thenReturn(entity);
        when(parameters.mapTimeOnly(any(), any())).thenReturn(Map.of(
                "startTime", "2025-01-01", "endTime", "2026-01-01"));
        when(query.execute(any(), any())).thenReturn(List.of(Map.of("ok", 1)));
        DiagnosisCaseEvidenceService service = new DiagnosisCaseEvidenceService(
                entities, parameters, renderer, query, detailExtractor,
                mrasExecution, new DetailResultCache(),
                mock(AgentModelRegistry.class), mock(AgentModelInvoker.class),
                new ObjectMapper(), mock(KnowledgeDataDictionary.class));
        DiagnosisCaseSnapshot snapshot = new DiagnosisCaseSnapshot(
                "DCASE_1", "H1", "U1", "S1", "IN_PROGRESS", "CASE_INVESTIGATION",
                "HXZD-001-001", "HXZD-001-001", "company-v1", "qwen7b",
                Map.of("recordField", "ENCOUNTER_ID", "recordId", "406549460220407808"),
                Map.of(), Map.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), "STANDARD", Map.of(), Map.of(), Map.of(), "now", "now");

        Map<String, Object> result = service.collect(snapshot,
                LocalDateTime.parse("2025-01-01T00:00:00"),
                LocalDateTime.parse("2026-01-01T00:00:00"));

        assertThat((Map<?, ?>) result.get("identifierMapping")).isEqualTo(Map.of(
                "recordType", "ENCOUNTER_ID",
                "recordIds", List.of("406549460220407808"),
                "businessSourceField", "encounterId",
                "realTargetField", "ENCOUNTER_ID"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) result.get("stages");
        assertThat(stages.get(0).get("sql")).asString()
                .contains("[encounterId]", "'2025-01-01'", "'2026-01-01'")
                .doesNotContain(":startTime", ":endTime", "TOP (50)");
        assertThat(stages.get(1).get("sql")).asString()
                .contains("[ENCOUNTER_ID]").doesNotContain("TOP (50)");
        @SuppressWarnings("unchecked")
        Map<String, Object> display = (Map<String, Object>) result.get("display");
        assertThat(display)
                .containsEntry("conclusion",
                        "业务库、真实库和最终统计都取得了证据，可以继续判断该记录是否进入分母和分子。");
    }

    @Test
    void loadsFrozenNumeratorAndDenominatorDetailsAndReusesCache() {
        EntityPageParser entities = mock(EntityPageParser.class);
        MrasDetailSqlExtractor detailExtractor = mock(MrasDetailSqlExtractor.class);
        MrasSqlExecutionService mrasExecution = mock(MrasSqlExecutionService.class);
        when(detailExtractor.extract("HXZD-001-001", "HXZD-001-001"))
                .thenReturn(new MrasDetailSqlExtractor.DetailExtraction(
                        true, "SELECT * FROM detail", "hash-1", null,
                        MrasDetailKind.COUNT_RATIO, List.of("ENCOUNTER_ID"),
                        List.of(), "v1"));
        Map<String, Object> firstRow = new LinkedHashMap<>();
        firstRow.put("ENCOUNTER_ID", "E1");
        firstRow.put("CURRENT_DEPT_NAME", "骨伤一科");
        firstRow.put("入区时间", "2025-01-02 17:33:48");
        firstRow.put("转科时间", "2025-01-03 14:42:44");
        firstRow.put("转科时间-入院时间", 21);
        firstRow.put("转出科室", "内科");
        firstRow.put("转入科室", "骨伤一科");
        firstRow.put("IMRN", "ZY0001");
        firstRow.put("MODIFIED_AT", "2025-01-04 10:00:00");
        firstRow.put("LARGE_BUSINESS_ID", 470591365383204865L);
        firstRow.put("OPTIONAL_NAME", null);
        firstRow.put("__meets_numerator", 1);
        Map<String, Object> denominatorOnlyRow = new LinkedHashMap<>();
        denominatorOnlyRow.put("ENCOUNTER_ID", 460160609955387393L);
        denominatorOnlyRow.put("CURRENT_DEPT_NAME", "骨伤一科");
        denominatorOnlyRow.put("TRANSFER_WITHIN_TWO_DAY", 0);
        denominatorOnlyRow.put("__meets_numerator", 0);
        when(mrasExecution.executeBoundDetail(anyString(), eq("HXZD-001-001"),
                eq("HXZD-001-001"), any(), eq("hash-1"), any(), any(),
                anyLong(), anyLong())).thenReturn(ToolResult.success(
                        "ok", "ok", Map.of("rows", List.of(
                                firstRow,
                                denominatorOnlyRow))));
        when(mrasExecution.executePatientDetail(eq("HXZD-001-001"), eq("HXZD-001-001"),
                any(), any(), isNull(), eq("98175")))
                .thenReturn(ToolResult.success("ok", "ok", Map.of("rows", List.of(firstRow))));
        DiagnosisCaseEvidenceService service = new DiagnosisCaseEvidenceService(
                entities, mock(MrasParameterMapper.class), new MrasTemplateRenderer(),
                mock(IndicatorDatabaseQueryClient.class), detailExtractor,
                mrasExecution, new DetailResultCache(),
                mock(AgentModelRegistry.class), mock(AgentModelInvoker.class),
                new ObjectMapper(), mock(KnowledgeDataDictionary.class));
        service.setScreeningRules(publicRules());
        DiagnosisCaseSnapshot snapshot = new DiagnosisCaseSnapshot(
                "DCASE_2", "H1", "U1", "S1", "IN_PROGRESS",
                "CASE_CALIBER_CLARIFICATION", "HXZD-001-001", "HXZD-001-001",
                "company-v1", "qwen7b",
                Map.of("statStart", "2025-01-01T00:00:00",
                        "statEnd", "2026-01-01T00:00:00",
                        "issueDirection", "UNDER_INCLUDED",
                        "scopeType", "RECORD", "recordField", "ENCOUNTER_ID",
                        "recordIds", List.of("E1")),
                Map.of("ruleName", "转科比例"),
                Map.of("numeratorCount", 1, "denominatorCount", 2,
                        "overviewSqlHash", "hash-1",
                        "numeratorRule", "48小时内发生转科",
                        "denominatorRule", "同期入院患者"),
                List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), "STANDARD",
                Map.of(), Map.of(), Map.of(), "now", "now");

        Map<String, Object> numerator = service.details(snapshot, "numerator", 1, 50);
        Map<String, Object> denominator = service.details(snapshot, "denominator", 1, 50);
        Map<String, Object> searched = service.details(
                snapshot, "denominator", 1, 50, "E1", "骨伤一科");

        assertThat(numerator).containsEntry("rowCount", 1).containsEntry("snapshotReused", false);
        assertThat(denominator).containsEntry("rowCount", 2).containsEntry("snapshotReused", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> denominatorItems =
                (List<Map<String, Object>>) denominator.get("rows");
        assertThat(denominatorItems.get(0))
                .containsEntry("LARGE_BUSINESS_ID", "470591365383204865");
        assertThat(searched)
                .containsEntry("rowCount", 1)
                .containsEntry("unfilteredRowCount", 2)
                .containsEntry("search", "E1")
                .containsEntry("department", "骨伤一科");
        @SuppressWarnings("unchecked")
        List<String> departments = (List<String>) searched.get("departments");
        assertThat(departments).containsExactly("骨伤一科");

        Map<String, Object> screening = service.screenData(snapshot);
        assertThat(screening)
                .containsEntry("scannedRows", 2)
                .containsEntry("findingCount", 0)
                .containsEntry("modelUsed", false)
                .containsEntry("countsReconciled", true);

        Map<String, Object> clarification = service.clarifyScope(snapshot);
        assertThat(clarification)
                .containsEntry("issueDirection", "UNDER_INCLUDED")
                .containsEntry("traceMode", "FORWARD_MISSING")
                .containsEntry("status", "IN_NUMERATOR_AND_DENOMINATOR")
                .containsEntry("denominatorCount", 1L)
                .containsEntry("numeratorCount", 1L)
                .containsEntry("detailCountsReconciled", true);
        assertThat(clarification.get("summary")).asString()
                .contains("患者/记录 E1", "进入统计SQL分子结果");

        Map<String, Object> underClarification = service.clarifyDataConfirmation(
                snapshot, "UNDER_INCLUDED", Map.of(
                        "overIncludedDepartment", Map.of(
                                "field", "CURRENT_DEPT_NAME", "value", "骨伤一科"),
                        "underIncludedNote", "康复科一条记录没有出现在分母明细"));
        assertThat(underClarification)
                .containsEntry("scopeType", "DEPARTMENT")
                .containsEntry("object", "康复科")
                .containsEntry("status", "NOT_IN_DETAIL")
                .containsEntry("requestedMissingScope", "康复科一条记录没有出现在分母明细");
        assertThat(underClarification.get("summary")).asString()
                .contains("统计分子和分母明细中均未找到所选对象")
                .doesNotContain("骨伤一科没有出现在");
        assertThat(underClarification.get("naturalLanguageExplanation")).asString()
                .contains("确实不在当前统计分子明细中", "自动取证没有完成")
                .doesNotContain("知识库当前登记的统计口径", "这批数据从哪里来");

        Map<String, Object> overClarification = service.clarifyDataConfirmation(
                snapshot, "OVER_INCLUDED", Map.of("targets", List.of(Map.of(
                        "targetType", "RECORD", "field", "ENCOUNTER_ID",
                        "values", List.of("E1"), "labels", List.of("E1"),
                        "sourceGroup", "NUMERATOR_DETAIL"))));
        assertThat(overClarification)
                .containsEntry("status", "IN_NUMERATOR")
                .containsEntry("numeratorCount", 1L);
        assertThat(overClarification.get("naturalLanguageExplanation")).asString()
                .contains("统计分子明细", "2025-01-02 17:33:48", "入区",
                        "2025-01-03 14:42:44", "发生转科", "21小时8分钟", "从 内科 转到 骨伤一科",
                        "计入分子是正确的")
                .doesNotContain("分母", "这些数据怎样形成指标记录", "知识库当前登记",
                        "当前分子口径怎样判断", "MODIFIED_AT", "IMRN", "错误计算", "本不应计入");

        Map<String, Object> denominatorOnly = service.clarifyDataConfirmation(
                snapshot, "OVER_INCLUDED", Map.of("targets", List.of(Map.of(
                        "targetType", "RECORD", "field", "ENCOUNTER_ID",
                        "values", List.of("460160609955387393"),
                        "labels", List.of("分母患者"),
                        "sourceGroup", "DENOMINATOR_DETAIL"))));
        assertThat(denominatorOnly)
                .containsEntry("status", "IN_DENOMINATOR_ONLY")
                .containsEntry("numeratorCount", 0L)
                .containsEntry("denominatorCount", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> denominatorEvidence =
                (List<Map<String, Object>>) denominatorOnly.get("denominatorEvidenceRows");
        assertThat(denominatorEvidence.get(0))
                .containsEntry("ENCOUNTER_ID", "460160609955387393");
        assertThat(denominatorOnly.get("naturalLanguageExplanation")).asString()
                .contains("没有进入当前统计分子", "当前统计分母明细中找到1条记录",
                        "48小时内转科判定”为“否", "不满足当前分子口径",
                        "同期入院患者", "计入分母有明细依据");
    }

    @Test
    void searchesStructuredPatientCandidatesAcrossTargetAndBusinessFallback() {
        EntityPageParser entities = mock(EntityPageParser.class);
        MrasDetailSqlExtractor detailExtractor = mock(MrasDetailSqlExtractor.class);
        MrasSqlExecutionService execution = mock(MrasSqlExecutionService.class);
        IndicatorDatabaseQueryClient query = mock(IndicatorDatabaseQueryClient.class);
        EntityPageData entity = new EntityPageData(
                "HXZD-001-001", "转科比例", "", "HXZD-001-001", "推荐方案（公版）",
                "定义", "公式", "口径", "来源", "", "", "%", "", "",
                "SELECT ENCOUNTER_ID AS encounterId FROM INPATIENT_ENCOUNTER",
                "SELECT COUNT(1) FROM MRAS_BUSINESS_FIRSTVISIT", "", "",
                "CORE_FDR", "MRAS_BUSINESS_FIRSTVISIT", List.of(), List.of());
        when(entities.getEntity("HXZD-001-001", "H1")).thenReturn(entity);
        when(detailExtractor.extract("HXZD-001-001", "HXZD-001-001"))
                .thenReturn(new MrasDetailSqlExtractor.DetailExtraction(
                        true, "SELECT * FROM detail", "hash-candidate", null,
                        MrasDetailKind.COUNT_RATIO, List.of("ENCOUNTER_ID"),
                        List.of(), "v1"));
        when(execution.executeBoundDetail(anyString(), eq("HXZD-001-001"), eq("HXZD-001-001"),
                any(), eq("hash-candidate"), any(), any(), anyLong(), anyLong()))
                .thenReturn(ToolResult.success("ok", "ok", Map.of("rows", List.of(
                        Map.of("ENCOUNTER_ID", "E1", "FULL_NAME", "张三",
                                "__meets_numerator", 1)))));
        when(query.execute(any(), any())).thenAnswer(invocation -> {
            DatabaseRole role = invocation.getArgument(0, DatabaseRole.class);
            String sql = invocation.getArgument(1, String.class);
            if (sql.contains("INFORMATION_SCHEMA.COLUMNS")) {
                return List.of("ENCOUNTER_ID", "FULL_NAME", "IMRN", "BED_NO", "ADMITTED_AT")
                        .stream().map(value -> Map.<String, Object>of("COLUMN_NAME", value)).toList();
            }
            if (role == DatabaseRole.REAL && sql.contains("__candidate_count")) {
                return List.of(Map.of("__candidate_count", sql.contains("E2") ? 0L : 2L));
            }
            if (role == DatabaseRole.REAL && sql.contains("OFFSET")) {
                return List.of(
                        Map.of("ENCOUNTER_ID", "E1", "FULL_NAME", "张三", "BED_NO", "A01"),
                        Map.of("ENCOUNTER_ID", "E3", "FULL_NAME", "张三", "BED_NO", "A02"));
            }
            if (role == DatabaseRole.BUSINESS && sql.contains("__candidate_count")) {
                return List.of(Map.of("__candidate_count", 1L));
            }
            if (role == DatabaseRole.BUSINESS && sql.contains("OFFSET")) {
                return List.of(Map.of("ENCOUNTER_ID", "E2", "FULL_NAME", "李四",
                        "IMRN", "ZY002", "BED_NO", "B01", "ADMITTED_AT", "2025-02-03"));
            }
            return List.of();
        });
        DiagnosisCaseEvidenceService service = new DiagnosisCaseEvidenceService(
                entities, mock(MrasParameterMapper.class), new MrasTemplateRenderer(),
                query, detailExtractor, execution, new DetailResultCache(),
                mock(AgentModelRegistry.class), mock(AgentModelInvoker.class),
                new ObjectMapper(), mock(KnowledgeDataDictionary.class));
        DiagnosisCaseSnapshot snapshot = screeningSnapshot("hash-candidate", 1, 1);

        Map<String, Object> overCountedResult = service.searchPatientCandidates(
                snapshot, "OVER_COUNTED", "NAME_BED", "张三", "", "", "", "", "", 1, 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overCountedItems =
                (List<Map<String, Object>>) overCountedResult.get("items");
        assertThat(overCountedResult)
                .containsEntry("sourceLayer", "RECONCILED_DETAIL")
                .containsEntry("total", 1L);
        assertThat(overCountedItems).extracting(item -> item.get("membership"))
                .containsExactly("IN_NUMERATOR");

        Map<String, Object> targetResult = service.searchPatientCandidates(
                snapshot, "UNDER_COUNTED", "NAME_BED", "张三", "", "", "", "", "", 1, 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targetItems =
                (List<Map<String, Object>>) targetResult.get("items");
        assertThat(targetResult).containsEntry("sourceLayer", "TARGET").containsEntry("total", 2L);
        assertThat(targetItems).extracting(item -> item.get("membership"))
                .containsExactly("IN_NUMERATOR", "IN_TARGET_ONLY");

        service.searchPatientCandidates(snapshot, "UNDER_COUNTED", "IMRN_ADMISSION_DATE",
                "2025-02-03", "", "", "", "", "", 1, 50);
        service.searchPatientCandidates(snapshot, "UNDER_COUNTED", "NAME_IMRN",
                "ZY001", "", "", "", "", "", 1, 50);
        verify(query, atLeastOnce()).execute(eq(DatabaseRole.REAL),
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("[ADMITTED_AT]")
                        && sql.contains("CONVERT(date")));
        verify(query, atLeastOnce()).execute(eq(DatabaseRole.REAL),
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("[IMRN]")
                        && sql.contains("ZY001")));

        Map<String, Object> fallbackResult = service.searchPatientCandidates(
                snapshot, "UNDER_COUNTED", "ENCOUNTER_ID", "E2", "", "", "", "", "", 1, 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fallbackItems =
                (List<Map<String, Object>>) fallbackResult.get("items");
        assertThat(fallbackResult).containsEntry("sourceLayer", "BUSINESS_FALLBACK");
        assertThat(fallbackItems).hasSize(1);
        assertThat(fallbackItems.get(0))
                .containsEntry("encounterId", "E2")
                .containsEntry("targetPresent", false)
                .containsEntry("membership", "BUSINESS_ONLY");
    }

    @Test
    void usesCurrentSourceExtractionWhenPatientLookupHasNoTargetTable() {
        EntityPageParser entities = mock(EntityPageParser.class);
        MrasDetailSqlExtractor detailExtractor = mock(MrasDetailSqlExtractor.class);
        MrasSqlExecutionService execution = mock(MrasSqlExecutionService.class);
        IndicatorDatabaseQueryClient query = mock(IndicatorDatabaseQueryClient.class);
        SyncDataService syncDataService = mock(SyncDataService.class);
        EntityPageData entity = new EntityPageData(
                "HXZD-001-001", "转科比例", "", "HXZD-001-001", "推荐方案（公版）",
                "定义", "公式", "口径", "来源", "", "", "%", "", "",
                "SELECT ENCOUNTER_ID AS encounterId, FULL_NAME AS personName, "
                        + "IMRN AS imrn, ADMITTED_AT AS eventAt FROM INPATIENT_ENCOUNTER "
                        + "WHERE ADMITTED_AT BETWEEN :startTime AND :endTime",
                "SELECT COUNT(1)", "", "", "CORE_FDR", "", List.of(), List.of());
        when(entities.getEntity("HXZD-001-001", "H1")).thenReturn(entity);
        when(detailExtractor.extract("HXZD-001-001", "HXZD-001-001"))
                .thenReturn(new MrasDetailSqlExtractor.DetailExtraction(
                        true, "SELECT * FROM detail", "hash-source-candidate", null,
                        MrasDetailKind.COUNT_RATIO, List.of("ENCOUNTER_ID"),
                        List.of(), "v1"));
        when(execution.executeBoundDetail(anyString(), eq("HXZD-001-001"), eq("HXZD-001-001"),
                any(), eq("hash-source-candidate"), any(), any(), anyLong(), anyLong()))
                .thenReturn(ToolResult.success("ok", "ok", Map.of("rows", List.of())));
        when(syncDataService.queryTrustedBusinessSource(
                org.mockito.ArgumentMatchers.contains("COUNT(1)"), any()))
                .thenReturn(List.of(Map.of("__candidate_count", 1L)));
        when(syncDataService.queryTrustedBusinessSource(
                org.mockito.ArgumentMatchers.contains("FETCH NEXT 50"), any()))
                .thenReturn(List.of(Map.of("ENCOUNTER_ID", "E9", "PERSON_NAME", "王五",
                        "IMRN", "ZY009", "EVENT_AT", "2025-03-04")));
        DiagnosisCaseEvidenceService service = new DiagnosisCaseEvidenceService(
                entities, mock(MrasParameterMapper.class), new MrasTemplateRenderer(),
                query, detailExtractor, execution, new DetailResultCache(),
                mock(AgentModelRegistry.class), mock(AgentModelInvoker.class),
                new ObjectMapper(), mock(KnowledgeDataDictionary.class));
        service.setSqlDialects(new EntitySqlDialectResolver());
        service.setPatientCandidateDependencies(syncDataService, new SqlServerProperties());

        Map<String, Object> result = service.searchPatientCandidates(
                screeningSnapshot("hash-source-candidate", 0, 0),
                "UNDER_COUNTED", "NAME_BED", "", "", "", "", "", "", 1, 50);

        assertThat(result).containsEntry("sourceLayer", "SOURCE_EXTRACTION")
                .containsEntry("total", 1L).containsEntry("targetTableAvailable", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("encounterId", "E9")
                .containsEntry("targetPresent", true)
                .containsEntry("membership", "IN_TARGET_ONLY"));
    }

    @org.junit.jupiter.api.Disabled("测试知识库不固定启用 PUBLIC_001～003")
    @Test
    void screensThreePublicRulesAgainstFinalDetailsOnly() {
        EntityPageParser entities = mock(EntityPageParser.class);
        MrasDetailSqlExtractor detailExtractor = mock(MrasDetailSqlExtractor.class);
        MrasSqlExecutionService execution = mock(MrasSqlExecutionService.class);
        when(detailExtractor.extract("HXZD-001-001", "HXZD-001-001"))
                .thenReturn(new MrasDetailSqlExtractor.DetailExtraction(
                        true, "SELECT * FROM detail", "hash-public", null,
                        MrasDetailKind.COUNT_RATIO, List.of("EVENT_ID"),
                        List.of("EVENT_ID"), "v1"));
        Map<String, Object> testPatient = new LinkedHashMap<>();
        testPatient.put("EVENT_ID", "EVT-1");
        testPatient.put("ENCOUNTER_ID", "ENC-1");
        testPatient.put("FULL_NAME", "测试患者");
        testPatient.put("CURRENT_DEPT_NAME", "普通内科");
        testPatient.put("__meets_numerator", 0);
        Map<String, Object> dialysis = new LinkedHashMap<>();
        dialysis.put("EVENT_ID", "EVT-2");
        dialysis.put("ENCOUNTER_ID", "ENC-2");
        dialysis.put("FULL_NAME", "张三");
        dialysis.put("CURRENT_DEPT_NAME", "血液透析门诊");
        dialysis.put("__meets_numerator", 1);
        Map<String, Object> duplicate = new LinkedHashMap<>(dialysis);
        duplicate.put("ENCOUNTER_ID", "ENC-3");
        when(execution.executeBoundDetail(anyString(), eq("HXZD-001-001"), eq("HXZD-001-001"),
                any(), eq("hash-public"), any(), any(), anyLong(), anyLong()))
                .thenReturn(ToolResult.success("ok", "ok",
                        Map.of("rows", List.of(testPatient, dialysis, duplicate))));
        DiagnosisCaseEvidenceService service = new DiagnosisCaseEvidenceService(
                entities, mock(MrasParameterMapper.class), new MrasTemplateRenderer(),
                mock(IndicatorDatabaseQueryClient.class), detailExtractor, execution,
                new DetailResultCache(), mock(AgentModelRegistry.class),
                mock(AgentModelInvoker.class), new ObjectMapper(),
                mock(KnowledgeDataDictionary.class));
        service.setScreeningRules(publicRules());
        DiagnosisCaseSnapshot snapshot = screeningSnapshot("hash-public", 2, 3);

        Map<String, Object> screening = service.screenData(snapshot);

        assertThat(screening).containsEntry("findingCount", 5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) screening.get("findings");
        assertThat(findings).extracting(item -> item.get("ruleCode"))
                .containsExactlyInAnyOrder(
                        "PUBLIC_001", "PUBLIC_002", "PUBLIC_002", "PUBLIC_003", "PUBLIC_003");
        assertThat(findings.stream().filter(item -> "PUBLIC_003".equals(item.get("ruleCode")))
                .map(item -> item.get("sourceGroup")))
                .containsExactlyInAnyOrder("DENOMINATOR_DETAIL", "NUMERATOR_DETAIL");
        assertThat(findings).noneMatch(item -> String.valueOf(item.get("reason")).contains("事件启用"));
    }

    private static PublicDataScreeningRuleService publicRules() {
        return new PublicDataScreeningRuleService(new KnowledgeIndexResources(
                Path.of("src/main/resources/knowledge-index").toAbsolutePath().toString()));
    }

    private static DiagnosisCaseSnapshot screeningSnapshot(
            String hash, long numerator, long denominator) {
        return new DiagnosisCaseSnapshot(
                "DCASE_PUBLIC", "H1", "U1", "S1", "IN_PROGRESS",
                "CASE_INPUT", "HXZD-001-001", "HXZD-001-001", "company-v1", "model",
                Map.of("statStart", "2025-01-01T00:00:00",
                        "statEnd", "2026-01-01T00:00:00"),
                Map.of(), Map.of("numeratorCount", numerator, "denominatorCount", denominator,
                        "overviewSqlHash", hash), List.of(), List.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), "STANDARD", Map.of(), Map.of(), Map.of(), "now", "now");
    }

    @Test
    void departmentOptionsNeverTreatWardTimeAsAWardName() {
        assertThat(invokeDepartmentField("CURRENT_WARD_NAME")).isTrue();
        assertThat(invokeDepartmentField("CURRENT_DEPT_ID")).isTrue();
        assertThat(invokeDepartmentField("ADMITTED_TO_WARD_AT")).isFalse();
        assertThat(invokeDepartmentField("WARD_DISCHARGED_AT")).isFalse();
    }

    private static boolean invokeDepartmentField(String field) {
        try {
            var method = DiagnosisCaseEvidenceService.class
                    .getDeclaredMethod("isDepartmentField", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, field);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void explainsDepartmentCountsFromReconciledDetailRows() {
        EntityPageParser entities = mock(EntityPageParser.class);
        MrasDetailSqlExtractor extractor = mock(MrasDetailSqlExtractor.class);
        MrasSqlExecutionService execution = mock(MrasSqlExecutionService.class);
        AgentModelRegistry modelRegistry = mock(AgentModelRegistry.class);
        AgentModelInvoker models = mock(AgentModelInvoker.class);
        IndicatorDatabaseQueryClient query = mock(IndicatorDatabaseQueryClient.class);
        EntityPageData entity = new EntityPageData(
                "HXZD-001-001", "转科比例", "", "HXZD-001-001", "推荐方案（公版）",
                "定义", "公式", "口径", "来源", "", "", "%", "", "",
                "SELECT CURRENT_DEPT_NAME AS currentDeptName FROM INPATIENT_ENCOUNTER WHERE OUT_AT BETWEEN :startTime AND :endTime",
                "SELECT COUNT(1) FROM MRAS_BUSINESS_FIRSTVISIT", "", "", "CORE_FDR",
                "MRAS_BUSINESS_FIRSTVISIT", List.of("INPATIENT_ENCOUNTER"), List.of());
        when(entities.getEntity("HXZD-001-001", "H1")).thenReturn(entity);
        when(modelRegistry.requireInfo("qwen7b"))
                .thenReturn(new AgentModelInfo("qwen7b", "当前排查模型",
                        "openai-compatible", "deepseek-r1-distill-qwen-7b", false, 65_536, true));
        when(models.complete(eq("qwen7b"), any(), any(), any()))
                .thenReturn(new AgentModelInvoker.ModelCompletion(
                        "qwen7b", "{\"scopeExplanation\":\"本次核对骨伤一科为什么多算。\","
                                + "\"dataJourney\":\"住院业务记录按当前抽取规则整理为指标数据。\","
                                + "\"calculationRule\":\"统计时先判断分母，再判断分子。\","
                                + "\"actualEvidence\":\"业务记录三条，统计分母两条。\","
                                + "\"conclusion\":\"有一条记录在进入统计分母时减少。\"}"));
        when(extractor.extract("HXZD-001-001", "HXZD-001-001"))
                .thenReturn(new MrasDetailSqlExtractor.DetailExtraction(
                        true, "SELECT * FROM detail", "hash-1", null,
                        MrasDetailKind.COUNT_RATIO, List.of("ENCOUNTER_ID"),
                        List.of(), "v1"));
        when(execution.executeBoundDetail(anyString(), eq("HXZD-001-001"), eq("HXZD-001-001"),
                any(), eq("hash-1"), any(), any(), anyLong(), anyLong()))
                .thenReturn(ToolResult.success("ok", "ok", Map.of("rows", List.of(
                        Map.of("ENCOUNTER_ID", "E1", "CURRENT_DEPT_NAME", "骨伤一科",
                                "__meets_numerator", 1),
                        Map.of("ENCOUNTER_ID", "E2", "CURRENT_DEPT_NAME", "骨伤一科",
                                "__meets_numerator", 0),
                        Map.of("ENCOUNTER_ID", "E3", "CURRENT_DEPT_NAME", "外科",
                                "__meets_numerator", 0)))));
        when(query.execute(any(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(1, String.class);
            if (sql.contains("__evidence_count")) {
                return List.of(Map.of("__evidence_count", 3L));
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("CURRENT_DEPT_NAME", "骨伤一科");
            sample.put("OPTIONAL_VALUE", null);
            return List.of(sample);
        });
        DiagnosisCaseEvidenceService service = new DiagnosisCaseEvidenceService(
                entities, mock(MrasParameterMapper.class), new MrasTemplateRenderer(),
                query, extractor,
                execution, new DetailResultCache(),
                modelRegistry, models, new ObjectMapper(), mock(KnowledgeDataDictionary.class));
        DiagnosisCaseSnapshot snapshot = new DiagnosisCaseSnapshot(
                "DCASE_3", "H1", "U1", "S1", "IN_PROGRESS",
                "CASE_CALIBER_CLARIFICATION", "HXZD-001-001", "HXZD-001-001",
                "company-v1", "qwen7b",
                Map.of("statStart", "2025-01-01T00:00:00",
                        "statEnd", "2026-01-01T00:00:00",
                        "issueDirection", "UNDER_INCLUDED",
                        "scopeType", "DEPARTMENT", "scopeField", "CURRENT_DEPT_NAME",
                        "scopeValue", "骨伤一科"),
                Map.of("ruleName", "转科比例"),
                Map.of("numeratorCount", 1, "denominatorCount", 3,
                        "overviewSqlHash", "hash-1",
                        "numeratorRule", "48小时内发生转科",
                        "denominatorRule", "同期入院患者"),
                List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), "STANDARD",
                Map.of(), Map.of(), Map.of(), "now", "now");

        Map<String, Object> clarification = service.clarifyScope(snapshot);

        assertThat(clarification)
                .containsEntry("scopeType", "DEPARTMENT")
                .containsEntry("issueDirection", "UNDER_INCLUDED")
                .containsEntry("traceMode", "FORWARD_MISSING")
                .containsEntry("firstDifferenceStage", "统计SQL分母结果")
                .containsEntry("denominatorCount", 2L)
                .containsEntry("numeratorCount", 1L)
                .containsEntry("matchedFields", List.of("CURRENT_DEPT_NAME"))
                .containsEntry("explanationSource", "MODEL")
                .containsEntry("explanationModel", "qwen7b");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) clarification.get("stageEvidence");
        assertThat(stages).extracting(stage -> stage.get("stageKey"))
                .containsExactly("BUSINESS_SOURCE", "REAL_TARGET", "DENOMINATOR", "NUMERATOR");
        assertThat(stages.get(2)).containsEntry("status", "FOUND").containsEntry("count", 2L);
        assertThat(clarification.get("conclusion")).asString().contains("3条变为2条", "减少1条");
        assertThat(clarification.get("summary")).asString()
                .contains("科室/病区 骨伤一科", "统计SQL分母结果中有2条");
        assertThat(clarification.get("naturalLanguageExplanation")).asString()
                .contains("我先核对了你选择的对象", "这批数据从哪里来",
                        "正式统计如何判断分母和分子", "当前最早发现问题的环节和下一步")
                .doesNotContain("为什么多算");
        verify(models).complete(eq("qwen7b"), any(), any(), any());
    }

    @Test
    void preservesUnsafeSqlNumericIdentifiersAsStrings() {
        Map<String, Object> row = DiagnosisCaseEvidenceService.jsonSafeDetailRow(Map.of(
                "ENCOUNTER_ID", new BigDecimal("449185037926307841"),
                "BIZ_ID", new BigInteger("449185037926307842"),
                "COUNT_VALUE", new BigDecimal("3915")));

        assertThat(row.get("ENCOUNTER_ID")).isEqualTo("449185037926307841");
        assertThat(row.get("BIZ_ID")).isEqualTo("449185037926307842");
        assertThat(row.get("COUNT_VALUE")).isEqualTo(new BigDecimal("3915"));
    }

    @Test
    void keepsNonIntegralDecimalValuesNumeric() {
        Map<String, Object> row = DiagnosisCaseEvidenceService.jsonSafeDetailRow(Map.of(
                "RATE", new BigDecimal("0.019412515964"),
                "SAFE_ID", 123456789L));

        assertThat(row.get("RATE")).isEqualTo(new BigDecimal("0.019412515964"));
        assertThat(row.get("SAFE_ID")).isEqualTo(123456789L);
    }

    @Test
    void preservesUnsafeIdentifierInClarificationEvidenceRows() {
        Map<String, Object> row = DiagnosisCaseEvidenceService.numeratorEvidenceRow(Map.of(
                "就诊号", new BigDecimal("449185037926307841"),
                "患者姓名", "测试患者",
                "无关字段", "不应展示"));

        assertThat(row.get("就诊号")).isEqualTo("449185037926307841");
        assertThat(row).containsEntry("患者姓名", "测试患者")
                .doesNotContainKey("无关字段");
    }
}

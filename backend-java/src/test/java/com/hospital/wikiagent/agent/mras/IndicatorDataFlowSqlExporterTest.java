package com.hospital.wikiagent.agent.mras;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.agent.initialization.KnowledgeDataDictionary;
import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;

class IndicatorDataFlowSqlExporterTest {

    private static final Pattern UNRENDERED_PARAMETER = Pattern.compile(
            "(?<!:):[A-Za-z_][A-Za-z0-9_]*");

    @Test
    void rendersKnowledgeTemplateAsNavicatSqlServerScript() {
        DbHubProperties properties = new DbHubProperties();
        IndicatorDataFlowSqlExporter exporter = new IndicatorDataFlowSqlExporter(
                new MrasTemplateRenderer(), properties);
        String template = """
                SELECT s.ID, s.EVENT_AT
                FROM SOURCE_EVENT s #{NOLOCK}
                WHERE s.EVENT_AT >= :startTime AND s.EVENT_AT < :endTime
                #ETC{ AND s.DEPT_ID IN (:deptIdIn) }
                """;
        Map<String, Object> flow = Map.of("nodes", List.of(Map.of(
                "id", "source-extract",
                "databaseRole", "SYNC",
                "tableNames", List.of("SOURCE_EVENT"),
                "sql", template)));

        Map<String, Object> result = exporter.enrichFlow(
                flow,
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) ((List<?>) result.get("nodes")).get(0);
        String sql = String.valueOf(node.get("sql"));

        assertThat(sql)
                .contains("USE [WiNEX_All_DEV]", "DECLARE @startTime", "2025-01-01 00:00:00")
                .contains("FROM [WINDBA_GN].[SOURCE_EVENT] s WITH (NOLOCK)")
                .doesNotContain("#ETC", "#{NOLOCK}", ":startTime", "deptIdIn");
        assertThat(node.get("templateSql")).isEqualTo(template.strip());
        assertThat(node).containsEntry("sqlExecutable", true)
                .containsEntry("databaseName", "WiNEX_All_DEV")
                .containsEntry("schemaName", "WINDBA_GN");
        assertThat(node.get("parameters")).asList()
                .containsExactly("startTime", "endTime");
    }

    @Test
    void effectiveRulePreservesNullOptionalFields() {
        IndicatorDataFlowSqlExporter exporter = new IndicatorDataFlowSqlExporter(
                new MrasTemplateRenderer(), new DbHubProperties());
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("nodes", List.of());
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", "HXZD-001-001");
        rule.put("optionalField", null);
        rule.put("dataFlow", flow);

        Map<String, Object> result = exporter.enrichRule(
                rule, "2025-01-01 00:00:00", "2026-01-01 00:00:00");

        assertThat(result).containsEntry("optionalField", null)
                .containsKey("dataFlow");
    }

    @Test
    void allKnowledgeDataFlowSqlNodesAreRenderedWithoutTemplateMarkers() {
        String root = Path.of("src/main/resources/knowledge-index_backup_20260801_150233")
                .toAbsolutePath().normalize().toString();
        KnowledgeIndexResources resources = new KnowledgeIndexResources(root);
        EntityPageParser entities = new EntityPageParser(resources);
        MrasSqlLineageAnalyzer analyzer = new MrasSqlLineageAnalyzer();
        IndicatorDataFlowBuilder builder = new IndicatorDataFlowBuilder(
                analyzer,
                new IndicatorDataFlowTypeResolver(analyzer),
                new KnowledgeDataDictionary(resources));
        IndicatorDataFlowSqlExporter exporter = new IndicatorDataFlowSqlExporter(
                new MrasTemplateRenderer(), new DbHubProperties());

        int sqlNodeCount = 0;
        for (EntityPageData entity : entities.getAllEntities().values()) {
            Map<String, Object> flow = exporter.enrichFlow(
                    builder.build(entity),
                    LocalDateTime.of(2025, 1, 1, 0, 0),
                    LocalDateTime.of(2026, 1, 1, 0, 0));
            for (Object rawNode : (List<?>) flow.get("nodes")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> node = (Map<String, Object>) rawNode;
                if (!Boolean.TRUE.equals(node.get("sqlExecutable"))) continue;
                sqlNodeCount++;
                String sql = String.valueOf(node.get("sql"));
                assertThat(sql)
                        .as(entity.variantCode() + " / " + node.get("id"))
                        .contains("SQL Server / Navicat", "USE [")
                        .doesNotContain("#ETC", "#EQUALS", "#{NOLOCK}");
                assertThat(UNRENDERED_PARAMETER.matcher(sql).find())
                        .as(entity.variantCode() + " / " + node.get("id")
                                + " 不应残留知识库命名参数")
                        .isFalse();
            }
        }
        assertThat(sqlNodeCount).isGreaterThan(40);
    }
}

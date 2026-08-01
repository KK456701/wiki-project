package com.hospital.wikiagent.agent.initialization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;

class MrasSqlLineageAnalyzerTest {
    private final MrasSqlLineageAnalyzer analyzer = new MrasSqlLineageAnalyzer();

    @Test
    void excludesCtesAndKeepsNestedPhysicalTablesAndJoinFields() {
        String sql = """
                WITH DateSequence AS (
                    SELECT h.CALENDAR_DATE FROM HOLIDAY h
                ), Base AS (
                    SELECT e.ENCOUNTER_ID, t.ORIGIN_DEPT_ID
                    FROM INPATIENT_ENCOUNTER e
                    LEFT JOIN INPAT_TRANSFER t ON e.ENCOUNTER_ID = t.ENCOUNTER_ID
                )
                SELECT * FROM Base
                """;

        var lineage = analyzer.analyze(sql);

        assertThat(lineage.tables())
                .containsExactlyInAnyOrder("HOLIDAY", "INPATIENT_ENCOUNTER", "INPAT_TRANSFER")
                .doesNotContain("DATESEQUENCE", "BASE");
        assertThat(lineage.fieldsByTable().get("INPATIENT_ENCOUNTER"))
                .contains("ENCOUNTER_ID");
        assertThat(lineage.joins()).hasSize(1);
    }

    @Test
    void repeatedAliasAcrossNestedQueriesDoesNotInventTableFieldDependencies() {
        String sql = """
                SELECT a.ENCOUNTER_ID
                FROM MAHP_DMTS_MAIN a
                WHERE EXISTS (
                    SELECT 1 FROM INP_EMR_SECTION_DATA_ELEMENT a
                    WHERE a.INP_EMR_SECTION_ID IS NOT NULL
                )
                """;

        var lineage = analyzer.analyze(sql);

        assertThat(lineage.tables())
                .containsExactlyInAnyOrder("MAHP_DMTS_MAIN", "INP_EMR_SECTION_DATA_ELEMENT");
        assertThat(lineage.fieldsByTable())
                .doesNotContainKeys("MAHP_DMTS_MAIN", "INP_EMR_SECTION_DATA_ELEMENT");
        assertThat(lineage.warnings()).anyMatch(value -> value.contains("无法安全归属物理表"));
        assertThat(lineage.certain()).isTrue();
    }

    @Test
    void derivedTableOutputColumnsAreNotInventedOnInnerPhysicalTables() {
        String sql = """
                SELECT d.NURSING_GRADE_NAME
                FROM (
                    SELECT o.NAME AS NURSING_GRADE_NAME
                    FROM INP_CLI_ORDER o
                ) d
                """;

        var lineage = analyzer.analyze(sql);

        assertThat(lineage.tables()).containsExactly("INP_CLI_ORDER");
        assertThat(lineage.fieldsByTable().get("INP_CLI_ORDER"))
                .contains("NAME")
                .doesNotContain("NURSING_GRADE_NAME");
        assertThat(lineage.warnings()).anyMatch(value -> value.contains("D"));
        assertThat(lineage.certain()).isTrue();
    }

    @Test
    void everyCurrentEntitySqlResolvesAtLeastOnePhysicalTable() {
        EntityPageParser parser = new EntityPageParser();
        MrasTemplateRenderer renderer = new MrasTemplateRenderer();

        for (EntityPageData entity : parser.getAllEntities().values()) {
            if (entity.sourceTableSql() != null && !entity.sourceTableSql().isBlank()) {
                String source = renderer.renderTemplate(
                        entity.sourceTableSql(), Map.of("syncType", "outHosp"));
                assertThat(analyzer.analyze(source).tables())
                        .as(entity.variantCode() + " source")
                        .isNotEmpty()
                        .doesNotContain("DATESEQUENCE", "DEATHCASES", "WORKDAYCALC");
            }
            if (entity.overviewSql() != null && !entity.overviewSql().isBlank()) {
                String overview = renderer.renderTemplate(entity.overviewSql(), Map.of(
                        "marptBeginAt", "2025-01-01 00:00:00",
                        "marptEndAt", "2026-01-01 00:00:00"));
                assertThat(analyzer.analyze(overview).tables())
                        .as(entity.variantCode() + " overview")
                        .isNotEmpty();
            }
        }
    }
}

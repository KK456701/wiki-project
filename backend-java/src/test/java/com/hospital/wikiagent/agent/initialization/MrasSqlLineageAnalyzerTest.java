package com.hospital.wikiagent.agent.initialization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer.FieldRole;

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
        assertThat(lineage.fieldsByTable().get("MAHP_DMTS_MAIN")).contains("ENCOUNTER_ID");
        assertThat(lineage.fieldsByTable().get("INP_EMR_SECTION_DATA_ELEMENT"))
                .contains("INP_EMR_SECTION_ID");
        assertThat(lineage.unresolvedReferences()).isEmpty();
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
        assertThat(lineage.unresolvedReferences()).isEmpty();
        assertThat(lineage.certain()).isTrue();
    }

    @Test
    void derivedWildcardPassesPhysicalFieldsButKeepsComputedColumnsUnresolved() {
        String sql = """
                SELECT d.ORIGIN_DEPT_ID, d.RN
                FROM (
                    SELECT t.*, ROW_NUMBER() OVER (PARTITION BY t.ENCOUNTER_ID ORDER BY t.CREATED_AT) AS RN
                    FROM INPAT_TRANSFER t
                ) d
                """;

        var lineage = analyzer.analyze(sql);

        assertThat(lineage.fieldsByTable().get("INPAT_TRANSFER"))
                .contains("ORIGIN_DEPT_ID", "ENCOUNTER_ID", "CREATED_AT")
                .doesNotContain("RN");
        assertThat(lineage.unresolvedReferences())
                .anyMatch(value -> value.contains("派生表 D") && value.contains("D.RN"))
                .noneMatch(value -> value.contains("D.ORIGIN_DEPT_ID"));
    }

    @Test
    void sqlKeywordAfterUnrecognizedDisplayAliasIsNotInventedAsAField() {
        var lineage = analyzer.analyze("""
                SELECT CASE WHEN t.RESULT_VALUE >= t.中文目标值 THEN 'Y' ELSE 'N' END
                FROM RESULT_TABLE t
                """);

        assertThat(lineage.fieldsByTable().get("RESULT_TABLE"))
                .contains("RESULT_VALUE")
                .doesNotContain("THEN");
        assertThat(lineage.unresolvedReferences()).noneMatch(value -> value.contains("T.THEN"));
    }

    @Test
    void identicalAliasesInSiblingCtesStayInTheirOwnQueryScopes() {
        String sql = """
                WITH LeftSide AS (
                    SELECT t.ID AS ID FROM SOURCE_A t
                ), RightSide AS (
                    SELECT t.ID AS ID FROM SOURCE_B t
                )
                SELECT a.ID, b.ID
                FROM LeftSide a
                JOIN RightSide b ON a.ID = b.ID
                """;

        var lineage = analyzer.analyze(sql);

        assertThat(lineage.fieldsByTable().get("SOURCE_A")).contains("ID");
        assertThat(lineage.fieldsByTable().get("SOURCE_B")).contains("ID");
        assertThat(lineage.unresolvedReferences()).isEmpty();
    }

    @Test
    void fieldRolesSeparateDisplayFieldsFromCalculationFields() {
        String sql = """
                SELECT s.FULL_NAME,
                       COUNT(CASE WHEN s.RESULT_CODE = 'Y' THEN 1 END) AS numerator
                FROM SOURCE_TABLE s
                WHERE s.EVENT_AT >= :startTime
                GROUP BY s.PERSON_ID, s.FULL_NAME
                """;

        var lineage = analyzer.analyze(sql);

        assertThat(lineage.roles("SOURCE_TABLE", "RESULT_CODE"))
                .contains(FieldRole.NUMERATOR_CONDITION);
        assertThat(lineage.roles("SOURCE_TABLE", "EVENT_AT"))
                .contains(FieldRole.TIME_FILTER);
        assertThat(lineage.roles("SOURCE_TABLE", "PERSON_ID"))
                .contains(FieldRole.GROUP_KEY);
        assertThat(lineage.roles("SOURCE_TABLE", "FULL_NAME"))
                .contains(FieldRole.SELECT_ONLY, FieldRole.GROUP_KEY);
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

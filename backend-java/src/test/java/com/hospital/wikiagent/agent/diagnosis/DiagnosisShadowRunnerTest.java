package com.hospital.wikiagent.agent.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;

class DiagnosisShadowRunnerTest {

    @Test
    void recognizesPublicRuleCandidatesWithoutUsingUnrelatedCaseIds() {
        assertThat(DiagnosisShadowRunner.isPublicRuleCandidate(
                Map.of("publicRuleIds", List.of("PUBLIC_001")))).isTrue();
        assertThat(DiagnosisShadowRunner.isPublicRuleCandidate(
                Map.of("publicRuleIds", List.of()))).isFalse();
    }
    @Test
    void convertsStringValuesUsingTargetNumericTypeLikeFormalExtraction() {
        assertThat(DiagnosisShadowRunner.convertValueByType("991827", "numeric"))
                .isEqualTo(991827L);
        assertThat(DiagnosisShadowRunner.convertValueByType("2.40", "decimal"))
                .isEqualTo(new BigDecimal("2.40"));
        assertThat(DiagnosisShadowRunner.convertValueByType("", "nvarchar")).isNull();
        assertThat(DiagnosisShadowRunner.convertValueByType("张三", "nvarchar"))
                .isEqualTo("张三");
    }

    @Test
    void reportsTheDatabaseRootCauseInsteadOfTruncatingTheInsertStatementPrefix() {
        RuntimeException failure = new RuntimeException(
                "PreparedStatementCallback; SQL [a very long insert statement]",
                new IllegalStateException(
                        "不能将值 NULL 插入列 MRAS_BUSINESS_CONSULTATION_ID"));

        assertThat(DiagnosisCaseService.safeDiagnosticMessage(failure))
                .isEqualTo("不能将值 NULL 插入列 MRAS_BUSINESS_CONSULTATION_ID");
    }

    @Test
    void exclusionAcceptsReducedEventRowsWithoutRequiringEncounterToDisappear() {
        assertThat(DiagnosisShadowRunner.exclusionReducedAll(
                List.of("E1"), Map.of("E1", 34), Map.of("E1", 1))).isTrue();
        assertThat(DiagnosisShadowRunner.exclusionReducedAll(
                List.of("E1"), Map.of("E1", 34), Map.of("E1", 34))).isFalse();
    }

    @Test
    void duplicateCheckOnlyFlagsCountsIntroducedBeyondFormalBaseline() {
        assertThat(DiagnosisShadowRunner.newDuplicateKeys(
                Map.of("E1", 34), Map.of("E1", 1))).isEmpty();
        assertThat(DiagnosisShadowRunner.newDuplicateKeys(
                Map.of("E1", 1), Map.of("E1", 2))).containsExactly("E1");
        assertThat(DiagnosisShadowRunner.newDuplicateKeys(
                Map.of(), Map.of("E2", 2))).containsExactly("E2");
    }

    @Test
    void eventSnapshotEntityUsesTheSameRealDatabaseRoleAsFormalExtraction() {
        EntityPageData eventSnapshot = entity(
                "SELECT * FROM MRAS_PATIENT_EVENT", List.of(Map.entry("PatientRecord", "SELECT 1")));
        EntityPageData qualifiedEventSnapshot = entity(
                "SELECT * FROM [WINDBA_GN].[MRAS_PATIENT_EVENT] event",
                List.of(Map.entry("PatientRecord", "SELECT 1")));
        EntityPageData hybridSource = entity(
                "SELECT * FROM MRAS_PATIENT_EVENT event "
                        + "JOIN INP_SURGICAL_PLAN plan ON plan.ENCOUNTER_ID = event.ENCOUNTER_ID",
                List.of(Map.entry("PatientRecord", "SELECT 1")));
        EntityPageData businessSource = entity(
                "SELECT * FROM INPATIENT_ENCOUNTER", List.of());

        assertThat(eventSnapshot.sourceQueryFromReal()).isTrue();
        assertThat(qualifiedEventSnapshot.sourceQueryFromReal()).isTrue();
        assertThat(hybridSource.sourceQueryFromReal()).isFalse();
        assertThat(businessSource.sourceQueryFromReal()).isFalse();
    }

    @Test
    void shadowOverviewRemovesMarkdownQuoteLikeFormalCalculation() {
        DiagnosisShadowRunner runner = new DiagnosisShadowRunner(
                null, null, null, null, new MrasTemplateRenderer());

        String rendered = runner.renderExecutableOverview(
                "'  --查询出目标值\nWITH TargetValue AS (SELECT 1 AS target_value) SELECT * FROM TargetValue",
                Map.of());

        assertThat(rendered).startsWith("--查询出目标值\nWITH TargetValue");
        assertThat(rendered).doesNotStartWith("'");
    }

    @Test
    void auditTimestampColumnsAreSystemGeneratedLikeFormalExtraction() {
        assertThat(DiagnosisShadowRunner.isAuditTimestampColumn("CREATED_AT", "datetime2")).isTrue();
        assertThat(DiagnosisShadowRunner.isAuditTimestampColumn("modified_at", "datetime")).isTrue();
        assertThat(DiagnosisShadowRunner.isAuditTimestampColumn("CREATED_AT", "nvarchar")).isFalse();
        assertThat(DiagnosisShadowRunner.isAuditTimestampColumn("BUSINESS_AT", "datetime2")).isFalse();
    }

    @Test
    void shadowDdlIsRestrictedToSqlServerSessionTemporaryTables() {
        String create = DiagnosisShadowRunner.sessionShadowCreateSql(
                "MRAS_BUSINESS_FIRSTVISIT", "#DIAG_DCASE_1_ab12cd34");
        String drop = DiagnosisShadowRunner.sessionShadowDropSql("#DIAG_DCASE_1_ab12cd34");

        assertThat(create).contains("INTO [#DIAG_DCASE_1_ab12cd34]")
                .contains("FROM [dbo].[MRAS_BUSINESS_FIRSTVISIT]")
                .doesNotContain("INTO [dbo]");
        assertThat(drop).contains("tempdb..#DIAG_DCASE_1_ab12cd34")
                .doesNotContain("[dbo]");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> DiagnosisShadowRunner.sessionShadowDropSql("dbo.MRAS_PATIENT"))
                .hasMessageContaining("临时表名");
    }

    @Test
    void durationRuleUsesExactTimestampComparisonInsteadOfDatediffBoundaryCounting() {
        String sqlServer = DiagnosisCaseService.deterministicCandidate(
                "SELECT t1.ENCOUNTER_ID FROM INPATIENT_ENCOUNTER t1 WHERE 1=1\n"
                        + "AND t1.DISCHARGED_FROM_WARD_AT IS NOT NULL\n"
                        + "AND t1.ADMITTED_TO_WARD_AT IS NOT NULL",
                "排除出院时间－入院时间大于等于8小时的记录", "SOURCE_EXTRACT");
        String oracle = DiagnosisCaseService.deterministicCandidate(
                "SELECT t1.ENCOUNTER_ID FROM INPATIENT_ENCOUNTER t1 WHERE 1=1\n"
                        + "AND t1.DISCHARGED_FROM_WARD_AT < TIMESTAMP '2026-01-01 00:00:00'\n"
                        + "AND t1.FIRST_ADMITTED_TO_WARD_AT IS NOT NULL",
                "出院时间减入院时间至少24小时", "SOURCE_EXTRACT");

        assertThat(sqlServer).contains("DATEADD(HOUR, 8, t1.ADMITTED_TO_WARD_AT)")
                .doesNotContain("DATEDIFF");
        assertThat(oracle)
                .contains("t1.DISCHARGED_FROM_WARD_AT >= t1.FIRST_ADMITTED_TO_WARD_AT + NUMTODSINTERVAL(24, 'HOUR')")
                .doesNotContain("DATEDIFF");
    }

    @Test
    void staleDurationCandidateIsMovedOutOfNestedQueryAndConvertedForOracle() {
        String stale = """
                SELECT t1.ENCOUNTER_ID
                FROM INPATIENT_ENCOUNTER t1
                LEFT JOIN (
                  SELECT t.* FROM INPAT_TRANSFER t
                  WHERE t.IS_DEL = 0
                  AND t1.DISCHARGED_FROM_WARD_AT >= DATEADD(HOUR, 8, t1.FIRST_ADMITTED_TO_WARD_AT)
                ) t2 ON t1.ENCOUNTER_ID = t2.ENCOUNTER_ID
                WHERE
                1 = 1
                """;

        String normalized = DiagnosisCaseService.normalizeDurationCandidateForExecution(
                stale, true);

        assertThat(normalized)
                .contains("WHERE\n1 = 1\n  AND t1.DISCHARGED_FROM_WARD_AT >= "
                        + "t1.FIRST_ADMITTED_TO_WARD_AT + NUMTODSINTERVAL(8, 'HOUR')")
                .doesNotContain("DATEADD")
                .doesNotContain("WHERE t.IS_DEL = 0\n  AND t1.DISCHARGED_FROM_WARD_AT");
    }

    @Test
    void excludingShortStaysAndConsultationRulesAreCompiledTogether() {
        String candidate = DiagnosisCaseService.deterministicCandidate(
                "SELECT t1.ENCOUNTER_ID, t1.ADMITTED_TO_WARD_AT, "
                        + "t1.DISCHARGED_FROM_WARD_AT, D.CONSULT_COMPLETED_AT, t2.PRESCRIBED_AT\n"
                        + "FROM INPATIENT_ENCOUNTER t1\n"
                        + "INNER JOIN INPATIENT_CONSULT_APPLY A ON t1.ENCOUNTER_ID=A.ENCOUNTER_ID\n"
                        + "LEFT JOIN INPATIENT_CONSULT_REPLY D ON A.INP_CONSULT_APPLY_ID=D.INP_CONSULT_APPLY_ID\n"
                        + "LEFT JOIN INP_CLI_ORDER t2 ON t1.ENCOUNTER_ID=t2.ENCOUNTER_ID\n"
                        + "WHERE 1 = 1\n",
                "排除出院时间－入院时间少于 8 小时的患者；排除作废会诊，会诊状态为 399329839；"
                        + "要求会诊完成时间不为空；要求会诊后医嘱 ID 不为空。",
                "SOURCE_EXTRACT");

        assertThat(candidate)
                .contains("DATEADD(HOUR, 8, t1.ADMITTED_TO_WARD_AT)")
                .contains("A.CONSULT_STATUS_CODE <> '399329839'")
                .contains("D.CONSULT_COMPLETED_AT IS NOT NULL")
                .contains("t2.PRESCRIBED_AT IS NOT NULL");
    }

    private static EntityPageData entity(
            String sourceSql, List<Map.Entry<String, String>> extendedEvents) {
        return new EntityPageData(
                "HXZD-TEST", "测试指标", "", "HXZD-TEST", "推荐方案（公版）",
                "", "", "", "", "", "", "", "", "",
                sourceSql, "SELECT 1", "", "", "EVENT", "MRAS_TEST",
                List.of(), extendedEvents);
    }
}

package com.hospital.wikiagent.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadOnlySqlValidatorTest {
    private final ReadOnlySqlValidator validator = new ReadOnlySqlValidator();

    @Test
    void acceptsRegisteredLegacyPeriodParameterPairWithoutRewritingSql() {
        String sql = """
                SELECT COUNT(*) AS denominator_count
                FROM MRAS_BUSINESS_FIRSTVISIT
                WHERE ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt AND :marptEndAt
                """;

        assertThat(validator.validate(sql, "MRAS_BUSINESS_FIRSTVISIT").ok()).isTrue();
    }

    @Test
    void rejectsIncompleteLegacyPeriodParameterPair() {
        String sql = """
                SELECT COUNT(*) AS denominator_count
                FROM MRAS_BUSINESS_FIRSTVISIT
                WHERE ADMITTED_TO_WARD_AT >= :marptBeginAt
                """;

        assertThat(validator.validate(sql, "MRAS_BUSINESS_FIRSTVISIT").ok()).isFalse();
    }

    @Test
    void acceptsSqlServerReplaceStringFunctionInReadOnlyQuery() {
        String sql = """
                SELECT REPLACE(REPLACE(e.DISPLAY_NAME, '0', ''), '#', '') AS display_name
                FROM MRAS_PATIENT_EVENT e
                WHERE e.EVENT_AT >= :startTime AND e.EVENT_AT < :endTime
                """;

        assertThat(validator.validateReadOnly(sql).ok()).isTrue();
    }

    @Test
    void autonomousAdHocQueryStaysSingleStatementAndReadOnlyWithoutTemplateParameters() {
        assertThat(validator.validateAdHocReadOnly(
                "SELECT CURRENT_DEPT_NAME, COUNT_BIG(1) FROM MRAS_BUSINESS_NURSING GROUP BY CURRENT_DEPT_NAME").ok())
                .isTrue();
        assertThat(validator.validateAdHocReadOnly(
                "SELECT * FROM MRAS_BUSINESS_NURSING; DELETE FROM MRAS_BUSINESS_NURSING").ok())
                .isFalse();
    }

    @Test
    void adHocQueryRejectsSelectIntoAndForUpdate() {
        assertThat(validator.validateAdHocReadOnly(
                "SELECT * INTO COPIED_ROWS FROM MRAS_BUSINESS_NURSING").ok()).isFalse();
        assertThat(validator.validateAdHocReadOnly(
                "SELECT * FROM MRAS_BUSINESS_NURSING FOR UPDATE").ok()).isFalse();
    }

    @Test
    void adHocQueryRejectsExternalDatabaseLinksAndPackages() {
        assertThat(validator.validateAdHocReadOnly("SELECT * FROM PATIENT@REMOTE_HIS").ok()).isFalse();
        assertThat(validator.validateAdHocReadOnly(
                "SELECT UTL_HTTP.REQUEST('https://example.com') FROM DUAL").ok()).isFalse();
    }
}

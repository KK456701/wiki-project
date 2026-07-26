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
}

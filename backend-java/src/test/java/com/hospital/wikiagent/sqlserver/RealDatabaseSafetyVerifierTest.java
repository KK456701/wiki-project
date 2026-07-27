package com.hospital.wikiagent.sqlserver;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RealDatabaseSafetyVerifierTest {

    @Test
    void rejectsWrongDatabaseBeforeReadingWritableTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(identity("other", "writer", 0));

        assertThatIllegalStateException()
                .isThrownBy(() -> new RealDatabaseSafetyVerifier(jdbc).verify())
                .withMessage("REAL_DB_IDENTITY_MISMATCH");
        verify(jdbc, never()).queryForList(anyString(), eq(String.class));
    }

    @Test
    void rejectsSaAndAnySysadminLogin() {
        for (Map<String, Object> identity : List.of(
                identity("winex_aima", "sa", 0),
                identity("winex_aima", "unsafe_writer", 1))) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForMap(anyString())).thenReturn(identity);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new RealDatabaseSafetyVerifier(jdbc).verify())
                    .withMessage("REAL_DB_PERMISSION_UNSAFE");
            verify(jdbc, never()).queryForList(anyString(), eq(String.class));
        }
    }

    @Test
    void rejectsIncompleteDboSchemaAndCachesACompleteVerification() {
        JdbcTemplate incomplete = mock(JdbcTemplate.class);
        when(incomplete.queryForMap(anyString()))
                .thenReturn(identity("winex_aima", "writer", 0));
        when(incomplete.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("MRAS_BUSINESS_DEATH"));
        assertThatIllegalStateException()
                .isThrownBy(() -> new RealDatabaseSafetyVerifier(incomplete).verify())
                .withMessage("REAL_DB_SCHEMA_INCOMPLETE");

        JdbcTemplate safe = mock(JdbcTemplate.class);
        when(safe.queryForMap(anyString()))
                .thenReturn(identity("winex_aima", "writer", 0));
        when(safe.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.copyOf(RealDatabaseSafetyPolicy.TABLES));
        RealDatabaseSafetyVerifier verifier = new RealDatabaseSafetyVerifier(safe);
        verifier.verify();
        verifier.verify();
        verify(safe, times(1)).queryForMap(anyString());
        verify(safe, times(1)).queryForList(anyString(), eq(String.class));
    }

    private static Map<String, Object> identity(
            String database, String login, int sysadmin) {
        return Map.of(
                "database_name", database,
                "login_name", login,
                "is_sysadmin", sysadmin);
    }
}

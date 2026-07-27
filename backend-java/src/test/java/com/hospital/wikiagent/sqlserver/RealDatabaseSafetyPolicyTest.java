package com.hospital.wikiagent.sqlserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RealDatabaseSafetyPolicyTest {

    @Test
    void acceptsOnlyTheTwentyEightPublishedDboTables() {
        assertThat(RealDatabaseSafetyPolicy.TABLES).hasSize(28);
        assertThat(RealDatabaseSafetyPolicy.qualified("mras_business_death"))
                .isEqualTo("[dbo].[MRAS_BUSINESS_DEATH]");
    }

    @Test
    void rejectsCrossDatabaseSchemaAndInjectionIdentifiersBeforeSqlGeneration() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RealDatabaseSafetyPolicy.qualified(
                        "winex_aima.dbo.MRAS_BUSINESS_DEATH"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RealDatabaseSafetyPolicy.qualified(
                        "dbo.MRAS_BUSINESS_DEATH"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RealDatabaseSafetyPolicy.qualified(
                        "MRAS_BUSINESS_DEATH; DROP TABLE X"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RealDatabaseSafetyPolicy.qualified("UNLISTED_TABLE"));
    }

    @Test
    void sqlServerConfigurationRejectsSaWrongDatabaseAndLookalikeDatabaseProperty() {
        assertThatIllegalStateException()
                .isThrownBy(() -> properties(
                        "sa",
                        "jdbc:sqlserver://127.0.0.1:1433;databaseName=winex_aima")
                        .validate())
                .withMessageContaining("禁止使用 sa");
        assertThatIllegalStateException()
                .isThrownBy(() -> properties(
                        "winex_aima_writer",
                        "jdbc:sqlserver://127.0.0.1:1433;databaseName=other")
                        .validate())
                .withMessageContaining("databaseName=winex_aima");
        assertThatIllegalStateException()
                .isThrownBy(() -> properties(
                        "winex_aima_writer",
                        "jdbc:sqlserver://127.0.0.1:1433;databaseName=winex_aima_evil")
                        .validate())
                .withMessageContaining("databaseName=winex_aima");
        SqlServerProperties wrongSchema = properties(
                "winex_aima_writer",
                "jdbc:sqlserver://127.0.0.1:1433;databaseName=winex_aima");
        wrongSchema.setSchema("other");
        assertThatIllegalStateException()
                .isThrownBy(wrongSchema::validate)
                .withMessageContaining("winex_aima.dbo");
    }

    @Test
    void disabledConfigurationNeedsNoNetworkCredentials() {
        SqlServerProperties properties = new SqlServerProperties();
        properties.validate();
        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void writerProvisioningScriptKeepsConnectAndExactlyMatchesTheAllowlist()
            throws IOException {
        String script = Files.readString(Path.of(
                "..", "scripts", "sqlserver",
                "create-winex-aima-agent-writer.sql")).replace("\r\n", "\n");

        assertThat(script).contains(
                "GRANT CONNECT TO [winex_aima_agent_writer];",
                "REVOKE CONTROL TO [winex_aima_agent_writer];");
        assertThat(script).doesNotContain(
                "\nDENY CONTROL TO [winex_aima_agent_writer];");
        assertThat(script.lines()
                .filter(line -> line.startsWith(
                        "GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].["))
                .toList()).hasSize(RealDatabaseSafetyPolicy.TABLES.size());
        for (String table : RealDatabaseSafetyPolicy.TABLES) {
            assertThat(script).contains(
                    "GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].["
                            + table + "] TO [winex_aima_agent_writer];");
        }
    }

    private static SqlServerProperties properties(String username, String url) {
        SqlServerProperties properties = new SqlServerProperties();
        properties.setEnabled(true);
        properties.setUsername(username);
        properties.setPassword("test-only-password");
        properties.setUrl(url);
        return properties;
    }
}

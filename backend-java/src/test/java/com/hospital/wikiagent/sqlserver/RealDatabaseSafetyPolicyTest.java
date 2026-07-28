package com.hospital.wikiagent.sqlserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
    void disabledConfigurationDefaults() {
        SqlServerProperties properties = new SqlServerProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getUrl()).isBlank();
        assertThat(properties.getUsername()).isBlank();
        assertThat(properties.getPassword()).isBlank();
        assertThat(properties.getSchema()).isEqualTo("dbo");
        assertThat(properties.getHikari().getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    void applicationConfigurationContainsNoEmbeddedWriterCredentials()
            throws IOException {
        String yaml = Files.readString(Path.of(
                "src", "main", "resources", "application.yml"))
                .replace("\r\n", "\n");

        assertThat(yaml)
                .contains(
                        "enabled: ${WIKI_SQLSERVER_ENABLED:true}",
                        "url: ${WIKI_SQLSERVER_URL:}",
                        "username: ${WIKI_SQLSERVER_USERNAME:}",
                        "password: ${WIKI_SQLSERVER_PASSWORD:}",
                        "schema-name: ${DBHUB_BUSINESS_SCHEMA:WINDBA_GN}",
                        "property-naming-strategy: SNAKE_CASE",
                        "fail-on-unknown-properties: true")
                .doesNotContain(
                        "jdbc:sqlserver://172.",
                        "username: sa");
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
}

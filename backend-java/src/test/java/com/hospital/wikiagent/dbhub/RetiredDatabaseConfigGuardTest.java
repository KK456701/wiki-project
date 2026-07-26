package com.hospital.wikiagent.dbhub;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RetiredDatabaseConfigGuardTest {

    @Test
    void acceptsRoleBasedConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("wiki.dbhub.sources.business.source-id", "winex_all_dev")
                .withProperty("wiki.dbhub.sources.real.source-id", "winex_aima");

        assertThatCode(() -> new RetiredDatabaseConfigGuard(environment)
                .rejectRetiredConfiguration()).doesNotThrowAnyException();
    }

    @Test
    void rejectsRetiredTopLevelConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("wiki.dbhub.source-id", "win60_qa_991827");

        assertThatThrownBy(() -> new RetiredDatabaseConfigGuard(environment)
                .rejectRetiredConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wiki.dbhub.sources.business")
                .hasMessageContaining("wiki.dbhub.sources.real");
    }

    @Test
    void roleConfigurationRequiresFixedSourcesAndUniqueTools() {
        DbHubProperties properties = new DbHubProperties();
        assertThatCode(properties::validateSources).doesNotThrowAnyException();

        properties.businessSource().setSourceId("other_database");
        assertThatThrownBy(properties::validateSources)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DbHubProperties.BUSINESS_SOURCE_ID);

        properties.businessSource().setSourceId(DbHubProperties.BUSINESS_SOURCE_ID);
        properties.realSource().setExecuteTool(properties.businessSource().getExecuteTool());
        assertThatThrownBy(properties::validateSources)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不同");
    }
}

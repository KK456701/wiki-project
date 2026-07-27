package com.hospital.wikiagent.sqlserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class SqlServerAccessServiceTest {

    @Test
    void statusReportsDisabledWhenSqlServerIsOff() {
        SqlServerProperties properties = new SqlServerProperties();
        properties.setEnabled(false);
        properties.setSchema("WINDBA");

        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.containsBean("sqlServerJdbcTemplate")).thenReturn(false);

        SqlServerAccessService service = new SqlServerAccessService(properties, applicationContext);

        assertThat(service.status())
                .containsEntry("enabled", false)
                .containsEntry("schema", "WINDBA")
                .containsEntry("jdbc_ready", false);
        assertThat(service.probe()).isEmpty();
    }

    @Test
    void probeReturnsFailureWhenFactoryMissingWhileEnabled() {
        SqlServerProperties properties = new SqlServerProperties();
        properties.setEnabled(true);
        properties.setUrl("jdbc:sqlserver://127.0.0.1:1433;databaseName=demo");
        properties.setSchema("WINDBA");

        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.containsBean("sqlServerJdbcTemplate")).thenReturn(false);

        SqlServerAccessService service = new SqlServerAccessService(properties, applicationContext);
        Optional<Map<String, Object>> probe = service.probe();

        assertThat(probe).isPresent();
        assertThat(probe.get())
                .containsEntry("ok", false)
                .containsEntry("message", "sqlServerJdbcTemplate is not available");
        assertThat(service.status()).containsEntry("jdbc_ready", false);
    }
}

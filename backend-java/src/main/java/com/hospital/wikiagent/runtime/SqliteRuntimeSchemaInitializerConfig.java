package com.hospital.wikiagent.runtime;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Bind SQLite schema initialization to the primary runtime DataSource.
 *
 * <p>With wiki.sqlserver.enabled=true there are multiple DataSource beans.
 * Spring Boot SQL init is only auto-configured for a single candidate, and
 * EntityManagerFactory beans may otherwise wait on a miswired initializer that
 * tries to run sqlite-runtime-schema.sql against SQL Server.</p>
 */
@Configuration
@EnableConfigurationProperties(SqlInitializationProperties.class)
public class SqliteRuntimeSchemaInitializerConfig {

    @Bean
    @Primary
    SqlDataSourceScriptDatabaseInitializer dataSourceScriptDatabaseInitializer(
            @Qualifier("dataSource") DataSource dataSource,
            SqlInitializationProperties properties) {
        return new SqlDataSourceScriptDatabaseInitializer(dataSource, properties);
    }
}

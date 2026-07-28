package com.hospital.wikiagent.sqlserver;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 仅在显式启用时创建真实库受控写入所需的 JDBC Bean。
 *
 * <p>该数据源与本地 SQLite 运行库完全隔离，只供受控抽取网关在
 * {@code winex_aima} 白名单表上执行事务替换。关闭开关时不创建连接池，因此定义、
 * 口径和 SQL 展示不依赖真实库可用性。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class SqlServerJdbcConfig {

    @Bean(name = "sqlServerDataSource")
    DataSource sqlServerDataSource(SqlServerProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("sqlServerHikariPool");
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setMaximumPoolSize(properties.getHikari().getMaximumPoolSize());
        dataSource.setMinimumIdle(properties.getHikari().getMinimumIdle());
        dataSource.setConnectionTimeout(properties.getHikari().getConnectionTimeout());
        dataSource.addDataSourceProperty("useBulkCopyForBatchInsert", "true");
        return dataSource;
    }

    @Bean(name = "sqlServerJdbcTemplate")
    JdbcTemplate sqlServerJdbcTemplate(
            @Qualifier("sqlServerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "sqlServerTransactionManager")
    PlatformTransactionManager sqlServerTransactionManager(
            @Qualifier("sqlServerDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

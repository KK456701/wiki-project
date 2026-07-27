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
 * 创建仅供真实库受控替换使用的第二 JDBC 数据源。
 *
 * <p>第二数据源默认关闭且连接池最多两个连接，不参与 SQLite 运行库的自动配置。
 * 业务查询继续走只读 DBHub，只有事件抽取网关可以注入此处声明的写数据源。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class SqlServerWriteConfig {

    @Bean(name = "sqlServerDataSource", destroyMethod = "close")
    public HikariDataSource sqlServerDataSource(SqlServerProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("winexAimaWritePool");
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setMaximumPoolSize(Math.min(2, properties.getHikari().getMaximumPoolSize()));
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(properties.getHikari().getConnectionTimeout());
        return dataSource;
    }

    @Bean(name = "sqlServerJdbcTemplate")
    public JdbcTemplate sqlServerJdbcTemplate(
            @Qualifier("sqlServerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "sqlServerTransactionManager")
    public PlatformTransactionManager sqlServerTransactionManager(
            @Qualifier("sqlServerDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

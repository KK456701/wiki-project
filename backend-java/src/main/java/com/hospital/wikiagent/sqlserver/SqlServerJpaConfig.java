package com.hospital.wikiagent.sqlserver;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.StringUtils;

import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManagerFactory;

/**
 * 配置可选的 SQL Server 第二数据源与 Spring Data JPA。
 *
 * <p>仅在 {@code wiki.sqlserver.enabled=true} 时生效，避免干扰默认 SQLite 运行时库与既有 JDBC 仓储。
 * 实体与 Repository 必须放在 {@code com.hospital.wikiagent.sqlserver} 包内，避免与主数据源混扫。
 * 默认 JPA 自动配置已在 application.yml 中排除，因此这里手动创建 EntityManagerFactory。</p>
 */
@Configuration
@EnableTransactionManagement
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
@EnableJpaRepositories(
        basePackages = "com.hospital.wikiagent.sqlserver",
        entityManagerFactoryRef = "sqlServerEntityManagerFactory",
        transactionManagerRef = "sqlServerTransactionManager")
public class SqlServerJpaConfig {

    /**
     * 创建 SQL Server Hikari 连接池。
     */
    @Bean(name = "sqlServerDataSource")
    public DataSource sqlServerDataSource(SqlServerProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("sqlServerHikariPool");
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setMaximumPoolSize(properties.getHikari().getMaximumPoolSize());
        dataSource.setMinimumIdle(properties.getHikari().getMinimumIdle());
        dataSource.setConnectionTimeout(properties.getHikari().getConnectionTimeout());
        // 可选第二数据源：启动阶段不强制校验连通性，首次使用时再失败。
        // 开启 SQL Server JDBC 驱动的原生 Bulk Copy 批量插入协议支持
        dataSource.addDataSourceProperty("useBulkCopyForBatchInsert", "true");
        return dataSource;
    }

    /**
     * SQL Server dedicated JdbcTemplate for native JDBC write/sync paths.
     */
    @Bean(name = "sqlServerJdbcTemplate")
    public JdbcTemplate sqlServerJdbcTemplate(
            @Qualifier("sqlServerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 创建只扫描 SQL Server 实体包的 EntityManagerFactory。
     */
    @Bean(name = "sqlServerEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean sqlServerEntityManagerFactory(
            @Qualifier("sqlServerDataSource") DataSource dataSource,
            SqlServerProperties properties) {
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setShowSql(properties.getJpa().isShowSql());
        vendorAdapter.setGenerateDdl(false);
        if (StringUtils.hasText(properties.getJpa().getDatabasePlatform())) {
            vendorAdapter.setDatabasePlatform(properties.getJpa().getDatabasePlatform());
        }

        Map<String, Object> jpaProperties = new HashMap<>();
        jpaProperties.put("hibernate.hbm2ddl.auto", properties.getJpa().getDdlAuto());
        jpaProperties.put("hibernate.show_sql", properties.getJpa().isShowSql());
        jpaProperties.put("hibernate.format_sql", properties.getJpa().isShowSql());
        jpaProperties.put("hibernate.dialect", properties.getJpa().getDatabasePlatform());
        if (StringUtils.hasText(properties.getSchema())) {
            jpaProperties.put("hibernate.default_schema", properties.getSchema());
        }

        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPackagesToScan("com.hospital.wikiagent.sqlserver");
        factoryBean.setPersistenceUnitName("sqlServer");
        factoryBean.setJpaVendorAdapter(vendorAdapter);
        factoryBean.setJpaPropertyMap(jpaProperties);
        return factoryBean;
    }

    /**
     * SQL Server 事务管理器，仅供本包 JPA Repository 使用。
     */
    @Bean(name = "sqlServerTransactionManager")
    public PlatformTransactionManager sqlServerTransactionManager(
            @Qualifier("sqlServerEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
package com.hospital.wikiagent.sqlserver;

import javax.sql.DataSource;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * When the optional SQL Server DataSource is enabled, create the primary SQLite
 * runtime DataSource explicitly and mark related runtime beans as {@code @Primary}.
 *
 * <p>Spring Boot skips {@code DataSourceAutoConfiguration} once any
 * {@code DataSource} bean exists. Registering {@code sqlServerDataSource}
 * therefore prevents auto-creation of the default {@code dataSource}. Without an
 * explicit primary runtime bean, SQLite schema initialization and the existing
 * {@code JdbcTemplate} repositories fail to wire. Active only when
 * {@code wiki.sqlserver.enabled=true}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DataSourceProperties.class)
public class PrimaryRuntimeDataSourceConfigurer {

    /**
     * Build the SQLite runtime DataSource from {@code spring.datasource*} with a
     * fixed bean name of {@code dataSource}.
     */
    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        if (StringUtils.hasText(properties.getName())) {
            dataSource.setPoolName(properties.getName());
        }
        return dataSource;
    }

    /**
     * 显式创建绑定到 SQLite 主库的 {@link JdbcTemplate}，并标记为 {@code @Primary}。
     *
     * <p>Spring Boot 的 {@code JdbcTemplateAutoConfiguration} 在存在多个 DataSource 时
     * 依赖 {@code @ConditionalOnSingleCandidate} 判断是否创建。此处显式声明可确保注入到
     * 所有运行时初始化器（{@code AgentConversationMemory}、{@code AgentTraceSchemaInitializer}
     * 等）的 {@code JdbcTemplate} 指向 SQLite，而非 SQL Server。</p>
     */
    @Bean(name = "jdbcTemplate")
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Use a static BeanFactoryPostProcessor to avoid early configuration-class
     * dependency initialization.
     */
    @Bean
    public static BeanFactoryPostProcessor markRuntimeDataSourcePrimary() {
        return new BeanFactoryPostProcessor() {
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                markPrimaryIfPresent(beanFactory, "dataSource");
                markPrimaryIfPresent(beanFactory, "transactionManager");
                markPrimaryIfPresent(beanFactory, "jdbcTemplate");
            }

            private void markPrimaryIfPresent(ConfigurableListableBeanFactory beanFactory, String beanName) {
                if (!beanFactory.containsBeanDefinition(beanName)) {
                    return;
                }
                BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
                definition.setPrimary(true);
            }
        };
    }
}
package com.hospital.wikiagent.agent.extraction;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为源数据抽取扩展点提供安全的缺省装配。
 *
 * <p>受控 JDBC 抽取网关只在 required 且 SQL Server 已启用时装配；其他模式保留
 * 不可用对象，使计算返回明确错误，而不是在 Spring 启动阶段因为缺少写入连接导致
 * 定义、口径和 SQL 展示等只读能力不可用。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SourceExtractionGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(SourceExtractionGateway.class)
    SourceExtractionGateway unavailableSourceExtractionGateway() {
        return new UnavailableSourceExtractionGateway();
    }
}

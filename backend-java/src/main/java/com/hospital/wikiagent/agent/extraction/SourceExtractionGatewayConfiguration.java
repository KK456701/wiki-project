package com.hospital.wikiagent.agent.extraction;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为源数据抽取扩展点提供安全的缺省装配。
 *
 * <p>真实接口适配器合并后只需声明一个 {@link SourceExtractionGateway} Bean，
 * 条件 Bean 就会自动退出。没有适配器时仍保留不可用对象，使双库 Workflow 可以
 * 返回明确错误，而不是在 Spring 启动阶段因为缺少依赖导致整个 Agent 不可用。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SourceExtractionGatewayConfiguration {

    @Bean
    @ConditionalOnMissingBean(SourceExtractionGateway.class)
    SourceExtractionGateway unavailableSourceExtractionGateway() {
        return new UnavailableSourceExtractionGateway();
    }
}

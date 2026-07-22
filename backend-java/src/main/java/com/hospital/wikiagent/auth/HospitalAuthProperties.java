package com.hospital.wikiagent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 {@code HospitalAuthProperties} 对应的类型化配置，避免业务代码直接读取环境变量。
 *
 * <p>配置由 Spring Boot 在启动阶段完成类型化绑定；缺失的安全关键值必须显式失败或保持安全默认值。业务代码不得再次从环境变量读取同一配置。</p>
 */
@ConfigurationProperties(prefix = "wiki.auth")
public class HospitalAuthProperties {
    private int sessionHours = 8;

    public int getSessionHours() {
        return sessionHours;
    }

    public void setSessionHours(int sessionHours) {
        this.sessionHours = sessionHours;
    }
}

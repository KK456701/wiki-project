package com.hospital.wikiagent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wiki.auth")
/**
 * 承载 {@code HospitalAuthProperties} 对应的类型化配置，避免业务代码直接读取环境变量。
 */
public class HospitalAuthProperties {
    private int sessionHours = 8;

    public int getSessionHours() {
        return sessionHours;
    }

    public void setSessionHours(int sessionHours) {
        this.sessionHours = sessionHours;
    }
}

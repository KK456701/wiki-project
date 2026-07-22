package com.hospital.wikiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动 Java 17、Spring Boot、调度器以及打包在 JAR 中的 Vue 3 页面。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WikiAgentJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(WikiAgentJavaApplication.class, args);
    }
}

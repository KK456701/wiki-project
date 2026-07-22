package com.hospital.wikiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启动 Java 17、Spring Boot、调度器以及打包在 JAR 中的 Vue 3 页面。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WikiAgentJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(WikiAgentJavaApplication.class, args);
    }
}

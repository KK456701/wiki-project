package com.hospital.wikiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WikiAgentJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(WikiAgentJavaApplication.class, args);
    }
}

package com.hospital.wikiagent.migration;

import java.util.Set;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class CutoverSafetyGuard {
    private final MigrationProperties properties;

    public CutoverSafetyGuard(MigrationProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        String authority = properties.getAuthorityRuntime() == null
                ? "" : properties.getAuthorityRuntime().strip().toLowerCase();
        if (!Set.of("python", "java").contains(authority)) {
            throw new IllegalStateException("MIGRATION_AUTHORITY_RUNTIME 只能是 python 或 java。");
        }
        if ("java".equals(authority) && !properties.isCutoverApproved()) {
            throw new IllegalStateException(
                    "Java 权威模式必须显式设置 MIGRATION_CUTOVER_APPROVED=true。");
        }
        if ("java".equals(authority)
                && (properties.getReadinessReportId() == null || properties.getReadinessReportId().isBlank())) {
            throw new IllegalStateException(
                    "Java 权威模式必须提供 MIGRATION_READINESS_REPORT_ID。");
        }
    }
}

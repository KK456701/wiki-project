package com.hospital.wikiagent.agent.validation;

import java.time.Instant;
import java.util.List;

/**
 * 定义 {@code ImplementationValidationReport} 的不可变数据载体。
 */
public record ImplementationValidationReport(
        String schemaVersion,
        String reportId,
        String hospitalId,
        String ruleId,
        String ruleName,
        String statStart,
        String statEnd,
        ValidationStageStatus overallStatus,
        List<ValidationStageResult> stages,
        String sqlId,
        String runId,
        Number resultValue,
        Number numeratorCount,
        Number denominatorCount,
        String fileKey,
        Instant createdAt) {

    public static final String VERSION = "implementation-validation-report-v1";

    public ImplementationValidationReport {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? VERSION : schemaVersion;
        ruleName = ruleName == null ? "" : ruleName;
        stages = stages == null ? List.of() : List.copyOf(stages);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

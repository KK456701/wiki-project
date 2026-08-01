package com.hospital.wikiagent.agent.initialization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hospital.wikiagent.agent.sql.DatabaseRole;

/**
 * 表示一次批量执行专属的双库初始化校验报告。报告同时保存逐口径运行决策、分类证据、
 * 实际校验 SQL 与聚合返回值，供批量编排器实施局部阻断，也供 Trace 详情页按分类展示。
 * 该对象始终属于当前批次，明确标记不跨批次复用，不能替代指标正式计算结果。
 */
public record InitializationValidationReport(
        String batchRunId,
        String hospitalId,
        String statStart,
        String statEnd,
        long durationMs,
        String qualityStatus,
        boolean businessConnected,
        boolean realConnected,
        List<ProfileValidation> profiles,
        List<ValidationItem> items) {

    public enum Decision { RUNNABLE, NO_SAMPLE, BLOCKED, SKIPPED }

    public record ProfileValidation(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            Decision decision,
            String errorCode,
            String message,
            Long businessSourceCount,
            String executionType) {}

    public record ValidationItem(
            String category,
            String severity,
            DatabaseRole databaseRole,
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String sourceSystem,
            String tableName,
            String fieldName,
            String fieldLabel,
            String scope,
            String statStart,
            String statEnd,
            Long actualCount,
            Long totalCount,
            Long nullCount,
            Long matchedCount,
            Long unmatchedCount,
            Double rate,
            boolean affectsCalculation,
            String action,
            String errorCode,
            String message,
            String sql,
            Map<String, Object> parameters,
            long durationMs,
            Long returnedRows,
            String databaseError,
            String impactLevel,
            List<String> fieldRoles,
            String queryScope,
            String physicalObjectKey,
            String evidenceSource) {}

    public ProfileValidation decision(String profileId) {
        return profiles.stream()
                .filter(profile -> profile.profileId().equals(profileId))
                .findFirst().orElse(null);
    }

    public Map<String, Object> toTraceOutput() {
        long runnable = count(Decision.RUNNABLE);
        long noSample = count(Decision.NO_SAMPLE);
        long blocked = count(Decision.BLOCKED);
        long skipped = count(Decision.SKIPPED);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("batchRunId", batchRunId == null ? "" : batchRunId);
        output.put("qualityStatus", qualityStatus);
        output.put("indicatorCount", profiles.stream().map(ProfileValidation::ruleId).distinct().count());
        output.put("profileCount", profiles.size());
        output.put("runnableCount", runnable);
        output.put("noSampleCount", noSample);
        output.put("blockedCount", blocked);
        output.put("skippedCount", skipped);
        output.put("missingTableCount", categoryCount("MISSING_TABLE"));
        output.put("missingColumnCount", categoryCount("MISSING_COLUMN"));
        output.put("emptySourceCount", categoryCount("NO_DATA"));
        output.put("nullFieldCount", categoryCount("NULL_RATE"));
        output.put("joinGapCount", categoryCount("JOIN_COVERAGE"));
        output.put("unsupportedCount", categoryCount("UNSUPPORTED"));
        output.put("distinctNullFieldCount", distinctObjectCount("NULL_RATE"));
        output.put("distinctJoinGapCount", distinctObjectCount("JOIN_COVERAGE"));
        output.put("distinctUnsupportedCount", distinctObjectCount("UNSUPPORTED"));
        output.put("businessConnected", businessConnected);
        output.put("realConnected", realConnected);
        output.put("durationMs", durationMs);
        output.put("reused", false);
        output.put("statStart", statStart);
        output.put("statEnd", statEnd);
        output.put("profiles", profiles);
        output.put("items", items);
        output.put("realDataStatus", "等待本次抽取");
        return Map.copyOf(output);
    }

    private long count(Decision decision) {
        return profiles.stream().filter(profile -> profile.decision() == decision).count();
    }

    private long categoryCount(String category) {
        return items.stream().filter(item -> category.equals(item.category())).count();
    }

    private long distinctObjectCount(String category) {
        return items.stream().filter(item -> category.equals(item.category()))
                .map(ValidationItem::physicalObjectKey).filter(value -> value != null && !value.isBlank())
                .distinct().count();
    }
}

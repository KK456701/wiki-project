package com.hospital.wikiagent.agent.batch;

/**
 * 批量计算中单个指标的结构化执行结果。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。结果来自
 * 试运行工具的结构化输出与生效规则证据，不包含 SQL 正文或患者级明细。</p>
 */
public record IndicatorExecutionResult(
        String ruleId,
        String ruleName,
        Status status,
        Double resultValue,
        Long numerator,
        Long denominator,
        String valueType,
        String unit,
        String calculationDisplay,
        Long sampleCount,
        Object targetValue,
        String targetDirection,
        String statStart,
        String statEnd,
        String runId,
        String errorCode,
        String errorMessage,
        long durationMs,
        String dataFreshness,
        String profileId,
        String profileLabel,
        String extractionId,
        String extractionStatus,
        String eventNo,
        String overviewSqlHash,
        String detailKind,
        String detailContractVersion) {

    /** 单指标执行结论的有限状态。 */
    public enum Status {
        /** 试运行成功且获得聚合数值。 */
        SUCCESS,
        /** 试运行成功但统计区间内没有可用样本。 */
        NO_SAMPLE,
        /** 执行或证据校验失败。 */
        FAILED
    }

    public IndicatorExecutionResult {
        ruleId = ruleId == null ? "" : ruleId.strip();
        ruleName = ruleName == null ? "" : ruleName.strip();
        status = status == null ? Status.FAILED : status;
        unit = unit == null || unit.isBlank() ? null : unit.strip();
        valueType = valueType == null || valueType.isBlank() ? null : valueType.strip();
        calculationDisplay = calculationDisplay == null || calculationDisplay.isBlank()
                ? null : calculationDisplay.strip();
        targetDirection = targetDirection == null || targetDirection.isBlank()
                ? null : targetDirection.strip();
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.strip();
        errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.strip();
        durationMs = Math.max(0, durationMs);
        dataFreshness = dataFreshness == null || dataFreshness.isBlank()
                ? null : dataFreshness.strip();
        profileId = profileId == null || profileId.isBlank()
                ? null : profileId.strip();
        profileLabel = profileLabel == null || profileLabel.isBlank()
                ? null : profileLabel.strip();
        extractionId = extractionId == null || extractionId.isBlank()
                ? null : extractionId.strip();
        extractionStatus = extractionStatus == null || extractionStatus.isBlank()
                ? null : extractionStatus.strip();
        eventNo = eventNo == null || eventNo.isBlank() ? null : eventNo.strip();
        overviewSqlHash = overviewSqlHash == null || overviewSqlHash.isBlank()
                ? null : overviewSqlHash.strip();
        detailKind = detailKind == null || detailKind.isBlank() ? null : detailKind.strip();
        detailContractVersion = detailContractVersion == null || detailContractVersion.isBlank()
                ? null : detailContractVersion.strip();
    }

    /** 兼容尚未携带明细契约元数据的既有调用。 */
    public IndicatorExecutionResult(
            String ruleId,
            String ruleName,
            Status status,
            Double resultValue,
            Long numerator,
            Long denominator,
            String valueType,
            String unit,
            String calculationDisplay,
            Long sampleCount,
            Object targetValue,
            String targetDirection,
            String statStart,
            String statEnd,
            String runId,
            String errorCode,
            String errorMessage,
            long durationMs,
            String dataFreshness,
            String profileId,
            String profileLabel,
            String extractionId,
            String extractionStatus,
            String eventNo) {
        this(ruleId, ruleName, status, resultValue, numerator, denominator,
                valueType, unit, calculationDisplay, sampleCount, targetValue,
                targetDirection, statStart, statEnd, runId, errorCode, errorMessage,
                durationMs, dataFreshness, profileId, profileLabel, extractionId,
                extractionStatus, eventNo, null, null, null);
    }

    /** 兼容尚未携带 Profile 与抽取元数据的结构化调用。 */
    public IndicatorExecutionResult(
            String ruleId,
            String ruleName,
            Status status,
            Double resultValue,
            Long numerator,
            Long denominator,
            String valueType,
            String unit,
            String calculationDisplay,
            Long sampleCount,
            Object targetValue,
            String targetDirection,
            String statStart,
            String statEnd,
            String runId,
            String errorCode,
            String errorMessage,
            long durationMs,
            String dataFreshness) {
        this(ruleId, ruleName, status, resultValue, numerator, denominator,
                valueType, unit, calculationDisplay, sampleCount, targetValue,
                targetDirection, statStart, statEnd, runId, errorCode, errorMessage,
                durationMs, dataFreshness, null, null, null, null, null,
                null, null, null);
    }

    /** 兼容尚不携带快照新鲜度的结构化调用。 */
    public IndicatorExecutionResult(
            String ruleId,
            String ruleName,
            Status status,
            Double resultValue,
            Long numerator,
            Long denominator,
            String valueType,
            String unit,
            String calculationDisplay,
            Long sampleCount,
            Object targetValue,
            String targetDirection,
            String statStart,
            String statEnd,
            String runId,
            String errorCode,
            String errorMessage,
            long durationMs) {
        this(ruleId, ruleName, status, resultValue, numerator, denominator,
                valueType, unit, calculationDisplay, sampleCount, targetValue,
                targetDirection, statStart, statEnd, runId, errorCode, errorMessage,
                durationMs, null, null, null, null, null, null,
                null, null, null);
    }

    /** 兼容既有普通比例结果构造入口。 */
    public IndicatorExecutionResult(
            String ruleId,
            String ruleName,
            Status status,
            Double resultValue,
            Long numerator,
            Long denominator,
            String unit,
            Object targetValue,
            String targetDirection,
            String statStart,
            String statEnd,
            String runId,
            String errorCode,
            String errorMessage,
            long durationMs) {
        this(ruleId, ruleName, status, resultValue, numerator, denominator,
                null, unit, null, null, targetValue, targetDirection,
                statStart, statEnd, runId, errorCode, errorMessage, durationMs,
                null, null, null, null, null, null,
                null, null, null);
    }

    /** 构造一个失败结果（不携带数值）。 */
    public static IndicatorExecutionResult failed(
            String ruleId, String ruleName, String code, String message) {
        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.FAILED, null, null, null,
                null, null, null, null, null, null,
                null, null, null, code, message, 0,
                null, null, null, null, null, null,
                null, null, null);
    }

    public static IndicatorExecutionResult failed(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String eventNo,
            String code,
            String message) {
        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.FAILED, null, null, null,
                null, null, null, null, null, null,
                null, null, null, code, message, 0,
                null, profileId, profileLabel, null, null, eventNo,
                null, null, null);
    }

    /** 构造一个在初始化校验阶段已确认无源数据的结果。 */
    public static IndicatorExecutionResult noSample(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel,
            String eventNo,
            String code,
            String message,
            String statStart,
            String statEnd) {
        return new IndicatorExecutionResult(
                ruleId, ruleName, Status.NO_SAMPLE, null, 0L, 0L,
                null, null, null, 0L, null, null,
                statStart, statEnd, null, code, message, 0,
                null, profileId, profileLabel, null, null, eventNo,
                null, null, null);
    }

    public boolean ok() {
        return status == Status.SUCCESS || status == Status.NO_SAMPLE;
    }
}

package com.hospital.wikiagent.agent.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 实现 {@code AgentRunState} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
public class AgentRunState {
    private String currentRuleId;
    private String currentUploadFileKey;
    private String currentCaliberProfileId;
    private String currentCaliberLabel;
    private String subtaskId;
    private String lastRunId;
    private String lastDiagnosisId;
    private String statStart;
    private String statEnd;
    private boolean statPeriodDefaulted;
    private int stepCount;
    private int replanCount;
    private int clarificationCount;
    private String lastClarificationType;
    private final List<String> failedPlanIds = new ArrayList<>();
    private final List<String> validatedSqlIds = new ArrayList<>();
    private final List<EvidenceFact> evidence = new ArrayList<>();
    private final List<String> evidenceIds = new ArrayList<>();
    private final List<ToolResult> lastToolResults = new ArrayList<>();
    private final Map<String, ToolResult> toolResultCache = new HashMap<>();
    private final Map<String, Integer> toolCallCounts = new LinkedHashMap<>();
    private final Map<String, ExtractionReceipt> extractionReceipts = new LinkedHashMap<>();
    private Consumer<WorkflowProgress> progressReporter = progress -> { };
    private String lastIntent;
    private String lastRuleName;

    public String currentRuleId() {
        return currentRuleId;
    }

    public void currentRuleId(String value) {
        currentRuleId = value == null || value.isBlank() ? null : value.strip();
    }

    public String currentUploadFileKey() {
        return currentUploadFileKey;
    }

    public void currentUploadFileKey(String value) {
        currentUploadFileKey = value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 记录当前子任务已经由 Wiki 解析并确认的候选口径。
     *
     * <p>这里只保存安全 profile 引用，不保存字段物理名、SQL 或患者数据。</p>
     */
    public void currentCaliber(String profileId, String label) {
        currentCaliberProfileId = normalize(profileId);
        currentCaliberLabel = normalize(label);
    }

    public String currentCaliberProfileId() {
        return currentCaliberProfileId;
    }

    public String currentCaliberLabel() {
        return currentCaliberLabel;
    }

    public String subtaskId() {
        return subtaskId;
    }

    public void subtaskId(String value) {
        subtaskId = value;
    }

    public String lastRunId() {
        return lastRunId;
    }

    public void lastRunId(String value) {
        lastRunId = value == null || value.isBlank() ? null : value.strip();
    }

    public String lastDiagnosisId() {
        return lastDiagnosisId;
    }

    public void lastDiagnosisId(String value) {
        lastDiagnosisId = value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 记录本轮已经由 {@code PlanValidator} 确定的统计区间。
     *
     * <p>统计区间属于执行事实，不能只依赖某个 SQL 工具是否恰好把它放进返回值。
     * 将其显式保存在运行状态中，能够保证公式解释、SQL 生成和试运行结束后都可写入
     * 结构化会话记忆，后续“这个 SQL 怎么写”等追问可以稳定复用同一时间范围。</p>
     */
    public void statPeriod(String start, String end) {
        statStart = start == null || start.isBlank() ? null : start.strip();
        statEnd = end == null || end.isBlank() ? null : end.strip();
    }

    public String statStart() {
        return statStart;
    }

    public String statEnd() {
        return statEnd;
    }

    /**
     * 标记当前统计区间是否为 SQL 准备缺时间时由服务端填充的默认周期。
     * 为 true 时应在回答里提示用户可指定具体时间范围调整。
     */
    public void statPeriodDefaulted(boolean value) {
        statPeriodDefaulted = value;
    }

    public boolean statPeriodDefaulted() {
        return statPeriodDefaulted;
    }

    public int stepCount() {
        return stepCount;
    }

    public void incrementStep() {
        stepCount++;
    }

    public int replanCount() {
        return replanCount;
    }

    public void incrementReplanCount() {
        replanCount++;
    }

    /**
     * 记录一次澄清反问，返回当前累计次数。
     * 同一会话中澄清次数 ≥ 3 时应强制使用默认值继续执行。
     */
    public int incrementClarification(String type) {
        clarificationCount++;
        lastClarificationType = type == null ? "" : type.strip();
        return clarificationCount;
    }

    public int clarificationCount() {
        return clarificationCount;
    }

    /**
     * 检测是否连续两次生成相同类型的澄清（死循环检测）。
     */
    public boolean isRepeatedClarification(String type) {
        return type != null && type.equals(lastClarificationType) && clarificationCount >= 2;
    }

    /**
     * 是否应该跳过澄清并使用默认值（计数≥3 或 连续重复）。
     */
    public boolean shouldSkipClarification(String type) {
        return clarificationCount >= 3 || isRepeatedClarification(type);
    }

    public List<String> failedPlanIds() {
        return failedPlanIds;
    }

    public List<String> validatedSqlIds() {
        return validatedSqlIds;
    }

    public List<EvidenceFact> evidence() {
        return evidence;
    }

    public List<String> evidenceIds() {
        return evidenceIds;
    }

    public List<ToolResult> lastToolResults() {
        return lastToolResults;
    }

    public Map<String, ToolResult> toolResultCache() {
        return toolResultCache;
    }

    public int noteToolCall(String fingerprint) {
        int count = toolCallCounts.getOrDefault(fingerprint, 0) + 1;
        toolCallCounts.put(fingerprint, count);
        return count;
    }

    /**
     * 单个指标子任务在同一统计周期内只允许完成一次源数据抽取。候选口径和后续
     * 明细诊断复用该安全回执，避免重复触发写真实库的外部接口。
     */
    public ExtractionReceipt extractionReceipt(String key) {
        return key == null ? null : extractionReceipts.get(key);
    }

    public void extractionReceipt(String key, ExtractionReceipt receipt) {
        if (key != null && !key.isBlank() && receipt != null) {
            extractionReceipts.put(key, receipt);
        }
    }

    /**
     * 注入单轮、非持久化的 Workflow 进度观察器。领域工具只上报安全汇总，不能在这里
     * 传递 SQL 正文或患者级行。
     */
    public void progressReporter(Consumer<WorkflowProgress> reporter) {
        progressReporter = reporter == null ? progress -> { } : reporter;
    }

    public void reportProgress(WorkflowProgress progress) {
        if (progress != null) progressReporter.accept(progress);
    }

    /**
     * 记录本轮 Planner 确认的意图和指标名，供会话记忆生成代码摘要（digest）。
     */
    public void lastIntent(String value) {
        lastIntent = value == null || value.isBlank() ? null : value.strip();
    }

    public String lastIntent() {
        return lastIntent;
    }

    public void lastRuleName(String value) {
        lastRuleName = value == null || value.isBlank() ? null : value.strip();
    }

    public String lastRuleName() {
        return lastRuleName;
    }

    public record WorkflowProgress(
            String nodeName,
            String nodeLabel,
            String status,
            long durationMs,
            Map<String, Object> safeOutput) {
        public WorkflowProgress {
            nodeName = nodeName == null ? "workflow_stage" : nodeName;
            nodeLabel = nodeLabel == null ? "推进业务流程" : nodeLabel;
            status = status == null ? "success" : status;
            durationMs = Math.max(0, durationMs);
            safeOutput = safeOutput == null ? Map.of() : Map.copyOf(safeOutput);
        }
    }

    public record ExtractionReceipt(
            String extractionId,
            long extractedRows,
            long insertedRows,
            long updatedRows,
            long rejectedRows,
            String sourceSnapshotId,
            String targetSnapshotId) {}

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

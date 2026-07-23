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
    private String subtaskId;
    private String lastRunId;
    private String lastDiagnosisId;
    private String statStart;
    private String statEnd;
    private int stepCount;
    private int replanCount;
    private final List<String> failedPlanIds = new ArrayList<>();
    private final List<String> validatedSqlIds = new ArrayList<>();
    private final List<EvidenceFact> evidence = new ArrayList<>();
    private final List<String> evidenceIds = new ArrayList<>();
    private final List<ToolResult> lastToolResults = new ArrayList<>();
    private final Map<String, ToolResult> toolResultCache = new HashMap<>();
    private final Map<String, Integer> toolCallCounts = new LinkedHashMap<>();
    private Consumer<WorkflowProgress> progressReporter = progress -> { };

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
     * 注入单轮、非持久化的 Workflow 进度观察器。领域工具只上报安全汇总，不能在这里
     * 传递 SQL 正文或患者级行。
     */
    public void progressReporter(Consumer<WorkflowProgress> reporter) {
        progressReporter = reporter == null ? progress -> { } : reporter;
    }

    public void reportProgress(WorkflowProgress progress) {
        if (progress != null) progressReporter.accept(progress);
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
}

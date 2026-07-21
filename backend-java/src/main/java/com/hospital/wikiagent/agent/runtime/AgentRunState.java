package com.hospital.wikiagent.agent.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentRunState {
    private String currentRuleId;
    private String currentUploadFileKey;
    private String subtaskId;
    private int stepCount;
    private final List<String> validatedSqlIds = new ArrayList<>();
    private final List<EvidenceFact> evidence = new ArrayList<>();
    private final List<String> evidenceIds = new ArrayList<>();
    private final List<ToolResult> lastToolResults = new ArrayList<>();
    private final Map<String, ToolResult> toolResultCache = new HashMap<>();
    private final Map<String, Integer> toolCallCounts = new LinkedHashMap<>();

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

    public int stepCount() {
        return stepCount;
    }

    public void incrementStep() {
        stepCount++;
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
}

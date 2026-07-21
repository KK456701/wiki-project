package com.hospital.wikiagent.agent.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ValidationStageResult(
        String stageId,
        String stageName,
        ValidationStageStatus status,
        String summary,
        List<String> findingCodes,
        Map<String, Object> safeDetails,
        long durationMs) {

    public ValidationStageResult {
        findingCodes = findingCodes == null ? List.of() : List.copyOf(findingCodes);
        safeDetails = safeDetails == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(safeDetails));
        durationMs = Math.max(0, durationMs);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stage_id", stageId);
        values.put("stage_name", stageName);
        values.put("status", status.value());
        values.put("summary", summary);
        values.put("finding_codes", findingCodes);
        values.put("safe_details", safeDetails);
        values.put("duration_ms", durationMs);
        return Collections.unmodifiableMap(values);
    }
}

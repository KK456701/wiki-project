package com.hospital.wikiagent.agent.runtime;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定义 {@code ToolResult} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record ToolResult(
        boolean ok,
        String status,
        String code,
        String summary,
        Map<String, Object> data,
        boolean retryable,
        boolean cacheReused,
        List<String> evidenceIds) {

    public ToolResult {
        data = data == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public static ToolResult success(String code, String summary, Map<String, Object> data) {
        return new ToolResult(true, "success", code, summary, data, false, false, List.of());
    }

    public static ToolResult failure(String status, String code, String summary, boolean retryable) {
        return new ToolResult(false, status, code, summary, Map.of(), retryable, false, List.of());
    }

    public ToolResult reused() {
        return new ToolResult(ok, status, code, summary, data, retryable, true, evidenceIds);
    }

    public ToolResult withEvidenceIds(List<String> values) {
        return new ToolResult(ok, status, code, summary, data, retryable, cacheReused, values);
    }
}

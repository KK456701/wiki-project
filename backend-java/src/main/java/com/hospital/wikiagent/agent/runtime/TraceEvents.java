package com.hospital.wikiagent.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runner 与复合外层共用的已完成 Trace 节点事件。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
final class TraceEvents {
    private TraceEvents() {
    }

    static long started() {
        return System.currentTimeMillis();
    }

    static void completed(
            AgentRunObserver observer,
            String traceId,
            String nodeName,
            String nodeType,
            long startedAt,
            String subtaskId,
            Map<String, Object> input,
            Map<String, Object> output,
            Object... attributes) {
        long endedAt = System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "trace_node");
        event.put("trace_id", traceId);
        event.put("node_id", id("NODE_"));
        event.put("node_name", nodeName);
        event.put("node_type", nodeType);
        event.put("status", "success");
        event.put("started_at_epoch_ms", startedAt);
        event.put("ended_at_epoch_ms", endedAt);
        event.put("duration_ms", Math.max(0, endedAt - startedAt));
        event.put("subtask_id", subtaskId == null ? "root" : subtaskId);
        event.put("input", input == null ? Map.of() : input);
        event.put("output", output == null ? Map.of() : output);
        for (int index = 0; index + 1 < attributes.length; index += 2) {
            if (attributes[index + 1] != null) {
                event.put(String.valueOf(attributes[index]), attributes[index + 1]);
            }
        }
        observer.onEvent(Map.copyOf(event));
    }

    static void failed(
            AgentRunObserver observer,
            String traceId,
            String nodeName,
            String nodeType,
            long startedAt,
            String subtaskId,
            String code,
            String message,
            Object... attributes) {
        long endedAt = System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "trace_node");
        event.put("trace_id", traceId);
        event.put("node_id", id("NODE_"));
        event.put("node_name", nodeName);
        event.put("node_type", nodeType);
        event.put("status", "failed");
        event.put("started_at_epoch_ms", startedAt);
        event.put("ended_at_epoch_ms", endedAt);
        event.put("duration_ms", Math.max(0, endedAt - startedAt));
        event.put("subtask_id", subtaskId == null ? "root" : subtaskId);
        event.put("input", Map.of());
        event.put("output", Map.of());
        event.put("error_code", code == null ? "RUNTIME_ERROR" : code);
        event.put("error_message", message == null ? "" : message);
        for (int index = 0; index + 1 < attributes.length; index += 2) {
            if (attributes[index + 1] != null) {
                event.put(String.valueOf(attributes[index]), attributes[index + 1]);
            }
        }
        observer.onEvent(Map.copyOf(event));
    }

    static void recorded(
            AgentRunObserver observer,
            String traceId,
            String nodeName,
            String nodeType,
            String status,
            long durationMs,
            String subtaskId,
            Map<String, Object> input,
            Map<String, Object> output,
            Object... attributes) {
        long endedAt = System.currentTimeMillis();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "trace_node");
        event.put("trace_id", traceId);
        event.put("node_id", id("NODE_"));
        event.put("node_name", nodeName);
        event.put("node_type", nodeType);
        event.put("status", status == null || status.isBlank() ? "success" : status);
        event.put("started_at_epoch_ms", endedAt - Math.max(0, durationMs));
        event.put("ended_at_epoch_ms", endedAt);
        event.put("duration_ms", Math.max(0, durationMs));
        event.put("subtask_id", subtaskId == null ? "root" : subtaskId);
        event.put("input", input == null ? Map.of() : input);
        event.put("output", output == null ? Map.of() : output);
        for (int index = 0; index + 1 < attributes.length; index += 2) {
            if (attributes[index + 1] != null) {
                event.put(String.valueOf(attributes[index]), attributes[index + 1]);
            }
        }
        observer.onEvent(Map.copyOf(event));
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

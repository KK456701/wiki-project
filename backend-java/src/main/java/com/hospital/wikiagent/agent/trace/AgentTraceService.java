package com.hospital.wikiagent.agent.trace;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.runtime.AgentRunObserver;
import com.hospital.wikiagent.agent.runtime.AgentRunResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import tools.jackson.databind.ObjectMapper;

@Service
public class AgentTraceService {
    public static final String VERSION = "java-agent-trace-v1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AgentTraceRepository repository;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> starts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();

    public AgentTraceService(AgentTraceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void start(
            String traceId, String sessionId, HospitalPrincipal principal, String userQuery) {
        long now = System.currentTimeMillis();
        starts.put(traceId, now);
        sequences.put(traceId, new AtomicInteger());
        try {
            repository.start(traceId, sessionId, principal.hospitalId(), principal.userId(),
                    shorten(userQuery, 4000), at(now));
        } catch (RuntimeException ignored) {
            // Trace 写入失败不能影响 Agent 主链。
        }
    }

    public AgentRunObserver observer(String traceId, AgentRunObserver downstream) {
        return event -> {
            if ("trace_node".equals(String.valueOf(event.get("event")))) {
                recordNode(traceId, event);
                return;
            }
            downstream.onEvent(event);
        };
    }

    public void finish(String traceId, AgentRunResult result) {
        String status = "final_answer".equals(result.stopReason()) ? "success"
                : "clarification".equals(result.stopReason()) ? "incomplete" : "failed";
        String intent = result.requestPlan() == null ? null : result.requestPlan().intent().name();
        finish(traceId, status, intent, result.answer(), "failed".equals(status) ? 1 : 0,
                "incomplete".equals(status) ? 1 : 0);
    }

    public void fail(String traceId, String message) {
        finish(traceId, "failed", null, message, 1, 0);
    }

    public Map<String, Object> get(String traceId, HospitalPrincipal principal) {
        Map<String, Object> trace = repository.get(traceId, principal.hospitalId());
        if (trace.isEmpty()) {
            throw new AgentTraceNotFoundException("TRACE_NOT_FOUND", "未找到本院可访问的运行链路。");
        }
        List<Map<String, Object>> nodes = castRows(trace.get("nodes"));
        List<Map<String, Object>> enhanced = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            Map<String, Object> value = new LinkedHashMap<>(node);
            String name = text(value.get("node_name"));
            value.put("node_title", title(name));
            value.put("processing_summary", processing(name));
            value.put("input_data", decode(text(value.get("input_summary"))));
            value.put("output_data", decode(text(value.get("output_summary"))));
            enhanced.add(value);
        }
        trace.put("nodes", enhanced);
        trace.put("evidence", repository.evidence(traceId, principal.hospitalId()));
        trace.put("trace_version", VERSION);
        trace.put("timing_summary", timing(enhanced));
        return trace;
    }

    private void finish(
            String traceId, String status, String intent, String answer, int errors, int fallbacks) {
        long ended = System.currentTimeMillis();
        Long started = starts.remove(traceId);
        sequences.remove(traceId);
        try {
            repository.finish(traceId, status, intent, answer, errors, fallbacks, at(ended),
                    started == null ? 0 : Math.max(0, ended - started));
        } catch (RuntimeException ignored) {
            // Trace 写入失败不能覆盖业务结果。
        }
    }

    private void recordNode(String traceId, Map<String, Object> event) {
        long ended = longValue(event.get("ended_at_epoch_ms"), System.currentTimeMillis());
        long duration = Math.max(0, longValue(event.get("duration_ms"), 0));
        long started = longValue(event.get("started_at_epoch_ms"), ended - duration);
        long traceStart = starts.getOrDefault(traceId, started);
        AtomicInteger sequence = sequences.computeIfAbsent(traceId, ignored -> new AtomicInteger());
        String errorCode = text(event.get("error_code"));
        try {
            repository.node(new AgentTraceRepository.TraceNode(
                    traceId, first(text(event.get("node_id")), id("NODE_")),
                    first(text(event.get("node_name")), "unknown"),
                    first(text(event.get("node_type")), "code"),
                    first(text(event.get("status")), "success"),
                    safeJson(event.get("input")), safeJson(event.get("output")),
                    errorCode, shorten(text(event.get("error_message")), 2000),
                    text(event.get("tool_name")), text(event.get("db_source")),
                    text(event.get("sql_id")), text(event.get("run_id")),
                    text(event.get("rule_id")), text(event.get("model_id")),
                    at(started), at(ended), duration, text(event.get("parent_node_id")),
                    first(text(event.get("subtask_id")), "root"), sequence.incrementAndGet(),
                    Math.max(0, started - traceStart), duration,
                    text(event.get("capability")), first(text(event.get("failure_class")), classify(errorCode)),
                    integer(event.get("input_tokens")), integer(event.get("output_tokens")),
                    Boolean.TRUE.equals(event.get("cache_reused")), integer(event.get("retry_count"), 0)));
        } catch (RuntimeException ignored) {
            // Trace 写入失败不能影响回答。
        }
    }

    private String safeJson(Object value) {
        if (value == null) return "{}";
        Object safe = sanitize(value);
        try {
            return shorten(objectMapper.writeValueAsString(safe), 12000);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String lower = name.toLowerCase();
                boolean secret = sensitiveKey(lower);
                safe.put(name, secret ? "[已脱敏]" : sanitize(item));
            });
            return safe;
        }
        if (value instanceof Iterable<?> values) {
            List<Object> safe = new ArrayList<>();
            for (Object item : values) safe.add(sanitize(item));
            return safe;
        }
        return value instanceof String text ? shorten(text, 4000) : value;
    }

    private Object decode(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception exception) {
            return value;
        }
    }

    private static Map<String, Object> timing(List<Map<String, Object>> nodes) {
        long llm = 0, tool = 0, code = 0, storage = 0;
        for (Map<String, Object> node : nodes) {
            long duration = longValue(node.get("duration_ms"), 0);
            switch (text(node.get("node_type"))) {
                case "llm" -> llm += duration;
                case "tool", "database" -> tool += duration;
                case "storage" -> storage += duration;
                default -> code += duration;
            }
        }
        return Map.of("llm_ms", llm, "tool_ms", tool, "code_ms", code, "storage_ms", storage);
    }

    private static String title(String name) {
        String safeName = name == null ? "" : name;
        return switch (safeName) {
            case "memory_load" -> "读取会话上下文";
            case "planner_llm" -> "规划业务目标";
            case "plan_compile" -> "编译业务计划";
            case "plan_validate" -> "校验业务计划";
            case "state_controller" -> "选择下一业务能力";
            case "deterministic_tool_dispatch" -> "编译受控工具调用";
            case "tool_result" -> "执行并观察工具结果";
            case "plan_verify" -> "校验证据完整性";
            case "final_answer_llm" -> "生成最终回答";
            case "response_guard" -> "检查回答协议";
            case "memory_save" -> "保存会话上下文";
            case "compound_split" -> "拆分复合指标请求";
            case "compound_subtask" -> "执行指标子任务";
            case "compound_merge" -> "按输入顺序合并结果";
            default -> safeName.isBlank() ? "未命名节点" : safeName;
        };
    }

    private static String processing(String name) {
        return switch (name == null ? "" : name) {
            case "planner_llm" -> "LLM 只生成业务 RequestPlan，不选择工具。";
            case "state_controller" -> "根据未完成事实选择下一项业务能力。";
            case "deterministic_tool_dispatch" -> "服务端按 CapabilitySpec 编译工具与参数。";
            case "plan_verify" -> "只接受医院、规则、周期和对象链一致的 Evidence。";
            case "final_answer_llm" -> "LLM 只根据 VerifiedEvidence 组织回答。";
            default -> title(name);
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object value) {
        return value instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
    }

    private static String classify(String code) {
        if (code == null || code.isBlank()) return null;
        String upper = code.toUpperCase();
        if (upper.contains("TIMEOUT")) return "TIMEOUT";
        if (upper.contains("PERMISSION") || upper.contains("FORBIDDEN")) return "PERMISSION";
        if (upper.contains("DB") || upper.contains("SQL")) return "DATABASE";
        if (upper.contains("TIME_RANGE") || upper.contains("AMBIGUOUS")) return "CLARIFICATION";
        return "TOOL_OR_RUNTIME";
    }

    private static boolean sensitiveKey(String key) {
        return key.contains("password") || key.contains("secret")
                || List.of("authorization", "api_key", "token", "access_token", "refresh_token",
                        "sql", "sql_text", "raw_sql", "generated_sql", "raw_rows", "rows",
                        "patient_rows").contains(key);
    }

    private static LocalDateTime at(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
    }
    private static String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private static String first(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static String shorten(String value, int limit) { if (value == null) return null; return value.length() <= limit ? value : value.substring(0, limit); }
    private static long longValue(Object value, long fallback) { try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); } catch (RuntimeException ignored) { return fallback; } }
    private static Integer integer(Object value) { try { return value == null ? null : Integer.valueOf(String.valueOf(value)); } catch (RuntimeException ignored) { return null; } }
    private static int integer(Object value, int fallback) { Integer parsed = integer(value); return parsed == null ? fallback : parsed; }

    public static class AgentTraceNotFoundException extends RuntimeException {
        private final String code;
        public AgentTraceNotFoundException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}

package com.hospital.wikiagent.agent.trace;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.runtime.AgentRunObserver;
import com.hospital.wikiagent.agent.runtime.AgentRunResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 记录单轮节点、父子关系、耗时和安全输入输出，并提供当前医院范围内的性能汇总。
 * 非敏感输入输出按原始长度保存，密码、令牌、受控 SQL 正文和患者原始行继续脱敏。
 *
 * <p>该服务负责按业务顺序组合依赖，并把可预期失败转换为稳定错误语义。它不允许模型直接访问数据库，也不允许上层绕过策略、Evidence 或医院隔离边界。</p>
 */
@Service
public class AgentTraceService {
    public static final String VERSION = "java-agent-trace-v1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AgentTraceRepository repository;
    private final ObjectMapper objectMapper;
    private final AgentModelProperties properties;
    private final Map<String, Long> starts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private final AtomicInteger startsSincePrune = new AtomicInteger();

    public AgentTraceService(AgentTraceRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, new AgentModelProperties());
    }

    @Autowired
    public AgentTraceService(
            AgentTraceRepository repository,
            ObjectMapper objectMapper,
            AgentModelProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void start(
            String traceId, String sessionId, HospitalPrincipal principal, String userQuery) {
        long now = System.currentTimeMillis();
        starts.put(traceId, now);
        sequences.put(traceId, new AtomicInteger());
        try {
            repository.start(traceId, sessionId, principal.hospitalId(), principal.userId(),
                    userQuery, at(now));
            if (startsSincePrune.incrementAndGet() >= 100) {
                startsSincePrune.set(0);
                repository.prune(LocalDateTime.now(ZONE).minusDays(
                        Math.max(1, properties.getTraceRetentionDays())));
            }
        } catch (RuntimeException ignored) {
            // Trace 写入失败不能影响 Agent 主链。
        }
    }

    public AgentRunObserver observer(String traceId, AgentRunObserver downstream) {
        return event -> {
            if ("trace_node".equals(String.valueOf(event.get("event")))) {
                recordNode(traceId, event);
                // Trace 节点的完整输入输出只进入审计存储；SSE 仅推送安全阶段摘要，
                // 让对话卡片能实时显示当前执行阶段，而不会重复传输 SQL 或业务明细。
                downstream.onEvent(stageUpdate(traceId, event));
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

    public void recordStandaloneNode(String traceId, Map<String, Object> event) {
        recordNode(traceId, event);
    }

    public void finishStandalone(
            String traceId, String status, String intent, String summary, int errors) {
        finish(traceId, status, intent, summary, errors, 0);
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

    public Map<String, Object> list(HospitalPrincipal principal, RunFilters filters) {
        List<Map<String, Object>> runs = repository.list(
                principal.hospitalId(), filters.startedAfter(), filters.startedBefore(),
                filters.status(), filters.modelId(), filters.toolName(), filters.failureClass(),
                filters.limit());
        return Map.of(
                "hospital_id", principal.hospitalId(),
                "count", runs.size(),
                "items", runs);
    }

    public Map<String, Object> metrics(HospitalPrincipal principal, RunFilters filters) {
        List<Map<String, Object>> runs = repository.list(
                principal.hospitalId(), filters.startedAfter(), filters.startedBefore(),
                filters.status(), filters.modelId(), filters.toolName(), filters.failureClass(), 500);
        List<String> traceIds = runs.stream().map(value -> text(value.get("trace_id")))
                .filter(java.util.Objects::nonNull).toList();
        List<Map<String, Object>> nodes = repository.nodesFor(traceIds);
        List<Long> durations = runs.stream().map(value -> longValue(value.get("duration_ms"), 0))
                .sorted().toList();
        Map<String, Integer> statuses = new LinkedHashMap<>();
        Map<String, MutableStats> tools = new LinkedHashMap<>();
        Map<String, MutableStats> models = new LinkedHashMap<>();
        Map<String, Map<String, Long>> trend = new java.util.TreeMap<>();
        Map<String, java.util.Set<String>> subtasks = new LinkedHashMap<>();
        java.util.Set<String> replans = new java.util.HashSet<>();
        java.util.Set<String> repeated = new java.util.HashSet<>();
        int slowRequests = 0;
        for (Map<String, Object> run : runs) {
            String status = first(text(run.get("final_status")), "unknown");
            statuses.merge(status, 1, Integer::sum);
            if (longValue(run.get("duration_ms"), 0) >= properties.getTraceSlowRequestMs()) slowRequests++;
            String day = day(run.get("started_at"));
            trend.computeIfAbsent(day, ignored -> trendRow()).merge("requests", 1L, Long::sum);
        }
        int llmCalls = 0;
        int llmTimeouts = 0;
        int slowLlmCalls = 0;
        for (Map<String, Object> node : nodes) {
            String traceId = text(node.get("trace_id"));
            String name = first(text(node.get("node_name")), "");
            String day = day(node.get("started_at"));
            long duration = longValue(node.get("duration_ms"), 0);
            Map<String, Long> daily = trend.computeIfAbsent(day, ignored -> trendRow());
            if ("planner_llm".equals(name)) daily.merge("planner_ms", duration, Long::sum);
            if (List.of("final_answer_llm", "executor_llm").contains(name)) {
                daily.merge("final_answer_ms", duration, Long::sum);
            }
            if ("plan_replan".equals(name)) replans.add(traceId);
            if ("AGENT_REPEATED_TOOL_CALL".equals(text(node.get("error_code")))) repeated.add(traceId);
            subtasks.computeIfAbsent(traceId, ignored -> new java.util.HashSet<>())
                    .add(first(text(node.get("subtask_id")), "root"));
            String tool = text(node.get("tool_name"));
            if (tool != null && !tool.isBlank() && "tool_result".equals(name)) {
                MutableStats value = tools.computeIfAbsent(tool, ignored -> new MutableStats());
                value.calls++;
                value.durationMs += duration;
                if (List.of("failed", "error").contains(text(node.get("status")))) value.failures++;
            }
            String model = first(text(node.get("model_id")), text(node.get("llm_model")));
            if (model != null && !model.isBlank() && "llm".equals(text(node.get("node_type")))) {
                MutableStats value = models.computeIfAbsent(model, ignored -> new MutableStats());
                value.calls++;
                value.durationMs += duration;
                value.inputTokens += longValue(node.get("input_tokens"), 0);
                value.outputTokens += longValue(node.get("output_tokens"), 0);
                llmCalls++;
                if (duration >= properties.getTraceSlowLlmMs()) slowLlmCalls++;
                if ("TIMEOUT".equals(text(node.get("failure_class")))) {
                    value.timeouts++;
                    llmTimeouts++;
                }
            }
        }
        int total = runs.size();
        long average = total == 0 ? 0 : Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(0));
        java.util.Set<String> compound = subtasks.entrySet().stream()
                .filter(entry -> entry.getValue().stream().filter(value -> !"root".equals(value)).count() > 1)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        Map<String, Long> durationByTrace = runs.stream().collect(java.util.stream.Collectors.toMap(
                value -> text(value.get("trace_id")), value -> longValue(value.get("duration_ms"), 0),
                (left, right) -> left));
        double toolFailureRate = tools.values().stream().mapToInt(value -> value.failures).sum()
                / (double) Math.max(1, tools.values().stream().mapToInt(value -> value.calls).sum());
        double timeoutRate = llmTimeouts / (double) Math.max(1, llmCalls);
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (slowRequests > 0) warnings.add(warning("SLOW_REQUEST", slowRequests + " 个请求超过慢请求阈值。"));
        if (slowLlmCalls > 0) warnings.add(warning("SLOW_LLM", slowLlmCalls + " 次模型调用超过慢模型阈值。"));
        if (toolFailureRate >= properties.getTraceToolFailureWarningRate()) warnings.add(warning("TOOL_FAILURE_RATE", "工具失败率达到 " + percent(toolFailureRate) + "。"));
        if (timeoutRate >= properties.getTraceTimeoutWarningRate()) warnings.add(warning("MODEL_TIMEOUT_RATE", "模型超时率达到 " + percent(timeoutRate) + "。"));
        return eventValues(
                "hospital_id", principal.hospitalId(),
                "request_count", total,
                "success_rate", ratio(statuses.getOrDefault("success", 0), total),
                "incomplete_rate", ratio(total - statuses.getOrDefault("success", 0), total),
                "latency_ms", Map.of("average", average, "p50", percentile(durations, .50),
                        "p95", percentile(durations, .95), "p99", percentile(durations, .99)),
                "status_counts", statuses,
                "trend", trend.entrySet().stream().map(entry -> eventValues(
                        "date", entry.getKey(), "requests", entry.getValue().get("requests"),
                        "planner_ms", entry.getValue().get("planner_ms"),
                        "final_answer_ms", entry.getValue().get("final_answer_ms"))).toList(),
                "tools", stats(tools, "tool_name"), "models", stats(models, "model_id"),
                "repeated_call_stop_rate", ratio(repeated.size(), total),
                "replan_rate", ratio(replans.size(), total),
                "compound_request_count", compound.size(),
                "compound_average_duration_ms", compound.isEmpty() ? 0 : Math.round(
                        compound.stream().mapToLong(value -> durationByTrace.getOrDefault(value, 0L)).average().orElse(0)),
                "warnings", warnings,
                "thresholds", Map.of(
                        "slow_request_ms", properties.getTraceSlowRequestMs(),
                        "slow_llm_ms", properties.getTraceSlowLlmMs(),
                        "tool_failure_warning_rate", properties.getTraceToolFailureWarningRate(),
                        "timeout_warning_rate", properties.getTraceTimeoutWarningRate()));
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
            // TEXT 字段能够保存完整 JSON；不再按字符数裁剪，避免长上下文或历史 SQL
            // 在关键位置被截断。安全边界仍由 sanitize 的字段级脱敏负责。
            return objectMapper.writeValueAsString(safe);
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
        return value;
    }

    /**
     * 将内部 Trace 节点转换为前端可消费的轻量状态事件。
     *
     * <p>这里只公开节点身份、类型、状态和耗时，不携带 input/output。完整参数由授权
     * 用户通过“查看链路”读取，既满足实时反馈，也避免同一份大上下文在 SSE 中反复发送。</p>
     */
    private static Map<String, Object> stageUpdate(String traceId, Map<String, Object> event) {
        String nodeName = text(event.get("node_name"));
        Map<String, Object> value = eventValues(
                "event", "stage_update",
                "trace_id", traceId,
                "node_name", nodeName,
                "node_type", first(text(event.get("node_type")), "code"),
                "status", first(text(event.get("status")), "success"),
                "message", title(nodeName),
                "duration_ms", longValue(event.get("duration_ms"), 0),
                "tool_name", text(event.get("tool_name")),
                "capability", text(event.get("capability")),
                "model_id", text(event.get("model_id")),
                "subtask_id", text(event.get("subtask_id")));
        return Map.copyOf(value);
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

    public static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("时间筛选必须使用 ISO 日期时间。", exception);
        }
    }

    private static List<Map<String, Object>> stats(Map<String, MutableStats> values, String nameKey) {
        return values.entrySet().stream().map(entry -> eventValues(
                nameKey, entry.getKey(), "calls", entry.getValue().calls,
                "failures", entry.getValue().failures, "timeouts", entry.getValue().timeouts,
                "duration_ms", entry.getValue().durationMs,
                "input_tokens", entry.getValue().inputTokens,
                "output_tokens", entry.getValue().outputTokens)).toList();
    }

    private static Map<String, Long> trendRow() {
        Map<String, Long> value = new LinkedHashMap<>();
        value.put("requests", 0L);
        value.put("planner_ms", 0L);
        value.put("final_answer_ms", 0L);
        return value;
    }

    private static String day(Object value) {
        String text = String.valueOf(value == null ? "" : value);
        return text.length() >= 10 ? text.substring(0, 10) : "unknown";
    }

    private static long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return 0;
        return values.get(Math.min(values.size() - 1, (int) ((values.size() - 1) * fraction)));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", value * 100);
    }

    private static Map<String, Object> warning(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    private static Map<String, Object> eventValues(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index + 1] != null) result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static String title(String name) {
        String safeName = name == null ? "" : name;
        if (safeName.startsWith("implementation_validation_")
                && !"implementation_validation_answer".equals(safeName)) {
            return "执行实施验收阶段 " + safeName.substring("implementation_validation_".length()).toUpperCase();
        }
        return switch (safeName) {
            case "indicator_rule_match" -> "规则精确识别指标";
            case "indicator_semantic_retrieval" -> "本地语义召回指标";
            case "indicator_llm_disambiguation" -> "模型候选内消歧";
            case "memory_load" -> "读取会话上下文";
            case "planner_llm" -> "规划业务目标";
            case "plan_replan" -> "重新规划业务目标";
            case "plan_compile" -> "编译业务计划";
            case "plan_validate" -> "校验业务计划";
            case "failure_router" -> "路由失败处理";
            case "state_controller" -> "选择下一业务能力";
            case "deterministic_tool_dispatch" -> "编译受控工具调用";
            case "tool_result" -> "执行并观察工具结果";
            case "plan_verify" -> "校验证据完整性";
            case "final_answer_llm" -> "生成最终回答";
            case "prepared_sql_answer" -> "生成受控 SQL 回答";
            case "implementation_validation_answer" -> "生成实施验收回答";
            case "response_guard" -> "检查回答协议";
            case "memory_save" -> "保存会话上下文";
            case "compound_split" -> "拆分复合指标请求";
            case "compound_subtask" -> "执行指标子任务";
            case "compound_merge" -> "按输入顺序合并结果";
            case "metadata_sync_dbhub" -> "同步数据库元数据";
            default -> safeName.isBlank() ? "未命名节点" : safeName;
        };
    }

    private static String processing(String name) {
        return switch (name == null ? "" : name) {
            case "indicator_rule_match" -> "用正式名称和已审核同义词确定性匹配指标。";
            case "indicator_semantic_retrieval" -> "对未命中片段执行本地字符语义召回，不调用模型。";
            case "indicator_llm_disambiguation" -> "LLM 只能从服务端候选 rule_id 中消歧。";
            case "planner_llm" -> "LLM 只生成业务 RequestPlan，不选择工具。";
            case "plan_replan" -> "仅在允许的方向性错误下由 LLM 重规划一次。";
            case "failure_router" -> "统一判断本次失败应重规划一次，还是直接澄清、拒绝或兜底。";
            case "state_controller" -> "根据未完成事实选择下一项业务能力。";
            case "deterministic_tool_dispatch" -> "服务端按 CapabilitySpec 编译工具与参数。";
            case "plan_verify" -> "只接受医院、规则、周期和对象链一致的 Evidence。";
            case "final_answer_llm" -> "LLM 只根据 VerifiedEvidence 组织回答。";
            case "prepared_sql_answer" -> "服务端从本轮私有 SQL 对象确定性生成回答，不调用 Final Answer LLM。";
            case "implementation_validation_answer" -> "服务端根据固定阶段报告确定性生成回答。";
            case "metadata_sync_dbhub" -> "经 DBHub 只读采集表目录和指标映射依赖字段。";
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
                        "sql", "sql_text", "sql_preview", "raw_sql", "generated_sql", "raw_rows", "rows",
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

    public record RunFilters(
            LocalDateTime startedAfter,
            LocalDateTime startedBefore,
            String status,
            String modelId,
            String toolName,
            String failureClass,
            int limit) {
        public RunFilters {
            limit = Math.max(1, Math.min(500, limit));
        }
    }

    private static final class MutableStats {
        int calls;
        int failures;
        int timeouts;
        long durationMs;
        long inputTokens;
        long outputTokens;
    }

    public static class AgentTraceNotFoundException extends RuntimeException {
        private final String code;
        public AgentTraceNotFoundException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}

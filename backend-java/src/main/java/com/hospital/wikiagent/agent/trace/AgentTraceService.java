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
import java.util.Set;
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
    public static final String VERSION = "java-agent-trace-v2";
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
            String name = text(value.get("nodeName"));
            FlowStage stage = flowStage(name, text(value.get("nodeType")));
            value.put("nodeTitle", title(name));
            value.put("processingSummary", processing(name));
            value.put("flowStage", stage.id());
            value.put("flowStageTitle", stage.title());
            value.put("flowStageOrder", stage.order());
            value.put("inputData", decode(text(value.get("inputSummary"))));
            Object outputData = decode(text(value.get("outputSummary")));
            value.put("outputData", outputData);
            Map<String, Object> readiness = capabilityReadiness(outputData);
            if (!readiness.isEmpty()) {
                value.put("capabilityReadiness", readiness);
            }
            enhanced.add(value);
        }
        trace.put("nodes", enhanced);
        trace.put("flowEdges", flowEdges(enhanced));
        trace.put("evidence", repository.evidence(traceId, principal.hospitalId()));
        trace.put("traceVersion", VERSION);
        trace.put("timingSummary", timing(enhanced));
        return trace;
    }

    /**
     * 将实现级节点归并为稳定的业务架构阶段。
     *
     * <p>阶段字段由后端统一派生，避免不同前端各自维护节点名称映射。新增或历史未知
     * 节点会按节点类型安全归类，仍可进入架构图而不会从链路中消失。</p>
     */
    private static FlowStage flowStage(String name, String nodeType) {
        String safeName = first(name, "");
        if (Set.of(
                "memory_load",
                "indicator_rule_match",
                "indicator_semantic_retrieval",
                "indicator_llm_disambiguation",
                "compound_split").contains(safeName)) {
            return FlowStage.CONTEXT;
        }
        if (safeName.startsWith("plan_alignment")
                || Set.of(
                        "planner_llm",
                        "followup_plan_resolve",
                        "plan_goal_alignment",
                        "plan_replan",
                        "low_confidence_clarification",
                        "multiple_indicator_clarification",
                        "unsupported_feature_guard").contains(safeName)) {
            return FlowStage.PLANNING;
        }
        if (Set.of(
                "plan_compile",
                "plan_validate",
                "state_controller",
                "deterministic_tool_dispatch",
                "failure_router").contains(safeName)) {
            return FlowStage.COMPILATION;
        }
        if (safeName.startsWith("difference_diagnosis_layer_")
                || safeName.startsWith("dual_")
                || Set.of(
                        "tool_result",
                        "source_extraction_prepare",
                        "source_data_extraction",
                        "business_overview",
                        "real_overview",
                        "compound_subtask",
                        "metadata_sync_dbhub").contains(safeName)
                || "tool".equals(nodeType)
                || "database".equals(nodeType)) {
            return FlowStage.EXECUTION;
        }
        if (Set.of(
                "plan_verify",
                "response_guard",
                "difference_diagnosis_conclusion",
                "dual_diagnosis_conclusion").contains(safeName)) {
            return FlowStage.VERIFICATION;
        }
        if (Set.of(
                "final_answer_llm",
                "prepared_sql_answer",
                "caliber_options_answer",
                "caliber_simulation_answer",
                "difference_diagnosis_answer",
                "compound_merge",
                "memory_save").contains(safeName)) {
            return FlowStage.ANSWER;
        }
        if ("storage".equals(nodeType)) return FlowStage.ANSWER;
        if ("llm".equals(nodeType)) return FlowStage.PLANNING;
        return FlowStage.EXECUTION;
    }

    private enum FlowStage {
        CONTEXT("context", "上下文与指标识别", 1),
        PLANNING("planning", "规划与目标校验", 2),
        COMPILATION("compilation", "IR编译与能力选择", 3),
        EXECUTION("execution", "工具与数据库执行", 4),
        VERIFICATION("verification", "Evidence验证与安全检查", 5),
        ANSWER("answer", "回答组织与会话保存", 6);

        private final String id;
        private final String title;
        private final int order;

        FlowStage(String id, String title, int order) {
            this.id = id;
            this.title = title;
            this.order = order;
        }

        String id() {
            return id;
        }

        String title() {
            return title;
        }

        int order() {
            return order;
        }
    }

    /**
     * 把容易混淆的内部状态拆成四项 Trace 能力，不再让普通回答直接输出
     * {@code documentation_only} 等治理术语。
     */
    private static Map<String, Object> capabilityReadiness(Object raw) {
        Map<String, Object> data = evidenceMap(raw);
        if (data.isEmpty()) return Map.of();
        boolean relevant = data.containsKey("executionStatus")
                || data.containsKey("overviewRuntimeEligible")
                || data.containsKey("sqlStatus")
                || data.containsKey("sqlCapabilities");
        if (!relevant) return Map.of();
        Map<String, Object> sqlCapabilities = mapValue(data.get("sqlCapabilities"));
        Map<String, Object> department = mapValue(sqlCapabilities.get("departmentDetail"));
        if (department.isEmpty()) department = mapValue(sqlCapabilities.get("department"));
        Map<String, Object> patient = mapValue(sqlCapabilities.get("patientDetail"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("知识治理状态", first(
                first(
                        text(data.get("governanceStatus")),
                        text(data.get("executionStatus"))),
                "未提供"));
        String sqlStatus = text(data.get("sqlStatus"));
        result.put("SQL展示能力", (sqlStatus != null && !"unavailable".equals(sqlStatus))
                || Boolean.TRUE.equals(data.get("referenceOnly")));
        result.put("双库概览试算能力",
                Boolean.TRUE.equals(data.get("overviewRuntimeEligible"))
                        || "available".equals(text(data.get("sqlStatus"))));
        result.put("科室明细诊断能力", validatedCapability(department));
        result.put("患者明细诊断能力", validatedCapability(patient));
        return result;
    }

    private static boolean validatedCapability(Map<String, Object> capability) {
        // status 可能缺失（text 返回 null），List.of 不可变列表 contains(null) 会抛 NPE
        String status = text(capability.get("status"));
        return status != null
                && List.of(
                        "static_validated", "metadata_validated", "compile_validated",
                        "trial_validated", "executable")
                        .contains(status)
                && !(capability.get("blockers") instanceof List<?> blockers && !blockers.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidenceMap(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((key, value) -> map.put(String.valueOf(key), value));
        if (map.containsKey("executionStatus")
                || map.containsKey("overviewRuntimeEligible")
                || map.containsKey("sqlCapabilities")) {
            return map;
        }
        for (String key : List.of("result", "data", "effectiveRule")) {
            Map<String, Object> nested = evidenceMap(map.get(key));
            if (!nested.isEmpty()) return nested;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /**
     * 根据持久化的父子关系和泳道顺序生成前端流程图边。
     *
     * <p>新 Trace 优先使用 {@code parent_node_id}；历史节点没有父节点时，按同一
     * {@code subtask_id} 的 sequence 补顺序边。该兼容层不改写历史数据，也不会把
     * 不同指标子任务错误串成一条链。</p>
     */
    private static List<Map<String, Object>> flowEdges(List<Map<String, Object>> nodes) {
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<String> nodeIds = nodes.stream()
                .map(node -> text(node.get("nodeId")))
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> pairs = new java.util.LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String from = text(node.get("parentNodeId"));
            String to = text(node.get("nodeId"));
            if (from == null || to == null || !nodeIds.contains(from)) continue;
            addFlowEdge(result, pairs, from, to, "parent", "");
        }
        Map<String, List<Map<String, Object>>> lanes = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String lane = first(text(node.get("subtaskId")), "root");
            lanes.computeIfAbsent(lane, ignored -> new ArrayList<>()).add(node);
        }
        for (List<Map<String, Object>> lane : lanes.values()) {
            lane.sort(java.util.Comparator
                    .comparingLong((Map<String, Object> node) ->
                            longValue(node.get("sequence"), Long.MAX_VALUE))
                    .thenComparingLong(node ->
                            longValue(node.get("startedOffsetMs"), Long.MAX_VALUE)));
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            for (int index = 0; index < lane.size(); index++) {
                Map<String, Object> target = lane.get(index);
                String nodeName = first(text(target.get("nodeName")), "node");
                int occurrence = occurrences.merge(nodeName, 1, Integer::sum);
                if (index == 0) continue;
                Map<String, Object> source = lane.get(index - 1);
                String from = text(source.get("nodeId"));
                String to = text(target.get("nodeId"));
                if (from == null || to == null) continue;
                String edgeType = edgeType(source, target);
                String label = occurrence > 1 ? "循环 " + occurrence : "";
                addFlowEdge(result, pairs, from, to, edgeType, label);
            }
        }
        return List.copyOf(result);
    }

    private static void addFlowEdge(
            List<Map<String, Object>> target,
            java.util.Set<String> pairs,
            String from,
            String to,
            String edgeType,
            String label) {
        String key = from + "→" + to;
        if (!pairs.add(key)) return;
        target.add(eventValues(
                "fromNodeId", from,
                "toNodeId", to,
                "edgeType", edgeType,
                "label", label));
    }

    private static String edgeType(
            Map<String, Object> source,
            Map<String, Object> target) {
        String targetName = first(text(target.get("nodeName")), "");
        if (targetName.contains("replan")) return "replan";
        if (List.of("failed", "error").contains(text(target.get("status")))
                || List.of("failed", "error").contains(text(source.get("status")))) {
            return "failure";
        }
        return "sequence";
    }

    public Map<String, Object> list(HospitalPrincipal principal, RunFilters filters) {
        List<Map<String, Object>> runs = repository.list(
                principal.hospitalId(), filters.startedAfter(), filters.startedBefore(),
                filters.status(), filters.modelId(), filters.toolName(), filters.failureClass(),
                filters.limit());
        return Map.of(
                "hospitalId", principal.hospitalId(),
                "count", runs.size(),
                "items", runs);
    }

    public Map<String, Object> metrics(HospitalPrincipal principal, RunFilters filters) {
        List<Map<String, Object>> runs = repository.list(
                principal.hospitalId(), filters.startedAfter(), filters.startedBefore(),
                filters.status(), filters.modelId(), filters.toolName(), filters.failureClass(), 500);
        List<String> traceIds = runs.stream().map(value -> text(value.get("traceId")))
                .filter(java.util.Objects::nonNull).toList();
        List<Map<String, Object>> nodes = repository.nodesFor(traceIds);
        List<Long> durations = runs.stream().map(value -> longValue(value.get("durationMs"), 0))
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
            String status = first(text(run.get("finalStatus")), "unknown");
            statuses.merge(status, 1, Integer::sum);
            if (longValue(run.get("durationMs"), 0) >= properties.getTraceSlowRequestMs()) slowRequests++;
            String day = day(run.get("startedAt"));
            trend.computeIfAbsent(day, ignored -> trendRow()).merge("requests", 1L, Long::sum);
        }
        int llmCalls = 0;
        int llmTimeouts = 0;
        int slowLlmCalls = 0;
        for (Map<String, Object> node : nodes) {
            String traceId = text(node.get("traceId"));
            String name = first(text(node.get("nodeName")), "");
            String day = day(node.get("startedAt"));
            long duration = longValue(node.get("durationMs"), 0);
            Map<String, Long> daily = trend.computeIfAbsent(day, ignored -> trendRow());
            if ("planner_llm".equals(name)) daily.merge("plannerMs", duration, Long::sum);
            if (List.of("final_answer_llm", "executor_llm").contains(name)) {
                daily.merge("finalAnswerMs", duration, Long::sum);
            }
            if ("plan_replan".equals(name)) replans.add(traceId);
            if ("AGENT_REPEATED_TOOL_CALL".equals(text(node.get("errorCode")))) repeated.add(traceId);
            subtasks.computeIfAbsent(traceId, ignored -> new java.util.HashSet<>())
                    .add(first(text(node.get("subtaskId")), "root"));
            String tool = text(node.get("toolName"));
            if (tool != null && !tool.isBlank() && "tool_result".equals(name)) {
                MutableStats value = tools.computeIfAbsent(tool, ignored -> new MutableStats());
                value.calls++;
                value.durationMs += duration;
                if (List.of("failed", "error").contains(text(node.get("status")))) value.failures++;
            }
            String model = first(text(node.get("modelId")), text(node.get("llmModel")));
            if (model != null && !model.isBlank() && "llm".equals(text(node.get("nodeType")))) {
                MutableStats value = models.computeIfAbsent(model, ignored -> new MutableStats());
                value.calls++;
                value.durationMs += duration;
                value.inputTokens += longValue(node.get("inputTokens"), 0);
                value.outputTokens += longValue(node.get("outputTokens"), 0);
                llmCalls++;
                if (duration >= properties.getTraceSlowLlmMs()) slowLlmCalls++;
                if ("TIMEOUT".equals(text(node.get("failureClass")))) {
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
                value -> text(value.get("traceId")), value -> longValue(value.get("durationMs"), 0),
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
                "hospitalId", principal.hospitalId(),
                "requestCount", total,
                "successRate", ratio(statuses.getOrDefault("success", 0), total),
                "incompleteRate", ratio(total - statuses.getOrDefault("success", 0), total),
                "latencyMs", Map.of("average", average, "p50", percentile(durations, .50),
                        "p95", percentile(durations, .95), "p99", percentile(durations, .99)),
                "statusCounts", statuses,
                "trend", trend.entrySet().stream().map(entry -> eventValues(
                        "date", entry.getKey(), "requests", entry.getValue().get("requests"),
                        "plannerMs", entry.getValue().get("plannerMs"),
                        "finalAnswerMs", entry.getValue().get("finalAnswerMs"))).toList(),
                "tools", stats(tools, "toolName"), "models", stats(models, "modelId"),
                "repeatedCallStopRate", ratio(repeated.size(), total),
                "replanRate", ratio(replans.size(), total),
                "compoundRequestCount", compound.size(),
                "compoundAverageDurationMs", compound.isEmpty() ? 0 : Math.round(
                        compound.stream().mapToLong(value -> durationByTrace.getOrDefault(value, 0L)).average().orElse(0)),
                "warnings", warnings,
                "thresholds", Map.of(
                        "slowRequestMs", properties.getTraceSlowRequestMs(),
                        "slowLlmMs", properties.getTraceSlowLlmMs(),
                        "toolFailureWarningRate", properties.getTraceToolFailureWarningRate(),
                        "timeoutWarningRate", properties.getTraceTimeoutWarningRate()));
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
        long ended = longValue(event.get("endedAtEpochMs"), System.currentTimeMillis());
        long duration = Math.max(0, longValue(event.get("durationMs"), 0));
        long started = longValue(event.get("startedAtEpochMs"), ended - duration);
        long traceStart = starts.getOrDefault(traceId, started);
        AtomicInteger sequence = sequences.computeIfAbsent(traceId, ignored -> new AtomicInteger());
        String errorCode = text(event.get("errorCode"));
        String nodeName = first(text(event.get("nodeName")), "unknown");
        boolean exposeValidationSql = Set.of(
                "batch_data_initialization_validation",
                "real_snapshot_data_validation").contains(nodeName);
        try {
            repository.node(new AgentTraceRepository.TraceNode(
                    traceId, first(text(event.get("nodeId")), id("NODE_")),
                    nodeName,
                    first(text(event.get("nodeType")), "code"),
                    first(text(event.get("status")), "success"),
                    safeJson(event.get("input")),
                    safeJson(event.get("output"), exposeValidationSql),
                    errorCode, shorten(text(event.get("errorMessage")), 2000),
                    text(event.get("toolName")), text(event.get("dbSource")),
                    text(event.get("sqlId")), text(event.get("runId")),
                    text(event.get("ruleId")), text(event.get("modelId")),
                    at(started), at(ended), duration, text(event.get("parentNodeId")),
                    first(text(event.get("subtaskId")), "root"), sequence.incrementAndGet(),
                    Math.max(0, started - traceStart), duration,
                    text(event.get("capability")), first(text(event.get("failureClass")), classify(errorCode)),
                    integer(event.get("inputTokens")), integer(event.get("outputTokens")),
                    Boolean.TRUE.equals(event.get("cacheReused")), integer(event.get("retryCount"), 0)));
        } catch (RuntimeException ignored) {
            // Trace 写入失败不能影响回答。
        }
    }

    private String safeJson(Object value) {
        return safeJson(value, false);
    }

    private String safeJson(Object value, boolean exposeValidationSql) {
        if (value == null) return "{}";
        Object safe = sanitize(value, exposeValidationSql);
        try {
            // TEXT 字段能够保存完整 JSON；不再按字符数裁剪，避免长上下文或历史 SQL
            // 在关键位置被截断。安全边界仍由 sanitize 的字段级脱敏负责。
            return objectMapper.writeValueAsString(safe);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private Object sanitize(Object value) {
        return sanitize(value, false);
    }

    private Object sanitize(Object value, boolean exposeValidationSql) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String lower = name.toLowerCase();
                boolean secret = sensitiveKey(lower)
                        && !(exposeValidationSql && sqlKey(lower));
                safe.put(name, secret ? "[已脱敏]" : sanitize(item, exposeValidationSql));
            });
            return safe;
        }
        if (value instanceof Iterable<?> values) {
            List<Object> safe = new ArrayList<>();
            for (Object item : values) safe.add(sanitize(item, exposeValidationSql));
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
        String nodeName = text(event.get("nodeName"));
        Map<String, Object> output = event.get("output") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()), Map.Entry::getValue,
                        (left, right) -> right, LinkedHashMap::new))
                : Map.of();
        Map<String, Object> value = eventValues(
                "event", "stage_update",
                "traceId", traceId,
                "nodeName", nodeName,
                "nodeType", first(text(event.get("nodeType")), "code"),
                "status", first(text(event.get("status")), "success"),
                "message", title(nodeName),
                "durationMs", longValue(event.get("durationMs"), 0),
                "toolName", text(event.get("toolName")),
                "capability", text(event.get("capability")),
                "modelId", text(event.get("modelId")),
                "subtaskId", text(event.get("subtaskId")),
                "indicatorCount", output.get("indicatorCount"),
                "profileCount", output.get("profileCount"),
                "runnableCount", output.get("runnableCount"),
                "noSampleCount", output.get("noSampleCount"),
                "blockedCount", output.get("blockedCount"),
                "skippedCount", output.get("skippedCount"));
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
            long duration = longValue(node.get("durationMs"), 0);
            switch (text(node.get("nodeType"))) {
                case "llm" -> llm += duration;
                case "tool", "database" -> tool += duration;
                case "storage" -> storage += duration;
                default -> code += duration;
            }
        }
        return Map.of("llmMs", llm, "toolMs", tool, "codeMs", code, "storageMs", storage);
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
                "durationMs", entry.getValue().durationMs,
                "inputTokens", entry.getValue().inputTokens,
                "outputTokens", entry.getValue().outputTokens)).toList();
    }

    private static Map<String, Long> trendRow() {
        Map<String, Long> value = new LinkedHashMap<>();
        value.put("requests", 0L);
        value.put("plannerMs", 0L);
        value.put("finalAnswerMs", 0L);
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
        return switch (safeName) {
            case "indicator_rule_match" -> "规则精确识别指标";
            case "indicator_semantic_retrieval" -> "本地语义召回指标";
            case "indicator_llm_disambiguation" -> "模型候选内消歧";
            case "memory_load" -> "读取会话上下文";
            case "planner_llm" -> "规划业务目标";
            case "followup_plan_resolve" -> "跨轮确定性解析";
            case "plan_goal_alignment" -> "校验目标与计划";
            case "plan_alignment_review_llm" -> "审核复杂口径目标";
            case "plan_replan" -> "重新规划业务目标";
            case "plan_alignment_revalidate" -> "复核替代计划";
            case "plan_alignment_deterministic_fallback" -> "生成受控修正计划";
            case "plan_compile" -> "编译业务计划";
            case "plan_validate" -> "校验业务计划";
            case "failure_router" -> "路由失败处理";
            case "state_controller" -> "选择下一业务能力";
            case "deterministic_tool_dispatch" -> "编译受控工具调用";
            case "tool_result" -> "执行并观察工具结果";
            case "plan_verify" -> "校验证据完整性";
            case "final_answer_llm" -> "生成最终回答";
            case "prepared_sql_answer" -> "生成受控 SQL 回答";
            case "caliber_options_answer" -> "整理口径选项";
            case "caliber_simulation_answer" -> "生成候选口径回答";
            case "difference_diagnosis_layer_1" -> "诊断范围预检";
            case "difference_diagnosis_layer_2" -> "实时结构核验";
            case "difference_diagnosis_layer_3" -> "执行当前口径";
            case "difference_diagnosis_layer_4" -> "试运行候选口径";
            case "difference_diagnosis_layer_5" -> "核对记录集合";
            case "difference_diagnosis_layer_6" -> "检查数据质量";
            case "difference_diagnosis_conclusion" -> "生成诊断结论";
            case "difference_diagnosis_answer" -> "整理差异诊断回答";
            case "dual_period_validation" -> "校验统计范围";
            case "source_extraction_prepare" -> "准备源数据抽取";
            case "source_data_extraction" -> "抽取数据到真实库";
            case "batch_data_initialization_validation" -> "数据初始化校验";
            case "real_snapshot_data_validation" -> "校验真实库本次数据";
            case "business_overview" -> "计算业务库概览";
            case "real_overview" -> "计算真实库概览";
            case "dual_comparison" -> "核对双库结果";
            case "dual_department_detail" -> "核对科室差异";
            case "dual_patient_detail" -> "核对患者明细";
            case "dual_diagnosis_conclusion" -> "生成诊断结论";
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
            case "followup_plan_resolve" -> "指标和目标可由结构化会话唯一确定，本轮未调用 LLM Planner。";
            case "plan_goal_alignment" -> "确定性核对用户目标、会话事实、指标和候选口径是否与计划一致。";
            case "plan_alignment_review_llm" -> "仅在规则无法确定的复杂语义下，从允许候选中审核目标口径。";
            case "plan_replan" -> "仅在允许的方向性错误下由 LLM 重规划一次。";
            case "plan_alignment_revalidate" -> "替代计划必须再次通过同一套目标一致性校验。";
            case "plan_alignment_deterministic_fallback" -> "模型仍未纠正且候选唯一时，由服务端生成受控计划。";
            case "failure_router" -> "统一判断本次失败应重规划一次，还是直接澄清、拒绝或兜底。";
            case "state_controller" -> "根据未完成事实选择下一项业务能力。";
            case "deterministic_tool_dispatch" -> "服务端按 CapabilitySpec 编译工具与参数。";
            case "plan_verify" -> "只接受医院、规则、周期和对象链一致的 Evidence。";
            case "final_answer_llm" -> "LLM 只根据 VerifiedEvidence 组织回答。";
            case "prepared_sql_answer" -> "服务端从本轮私有 SQL 对象确定性生成回答，不调用 Final Answer LLM。";
            case "caliber_options_answer" -> "服务端按 Profile 状态分类展示口径，不执行数据库。";
            case "caliber_simulation_answer" -> "服务端使用已验证候选 profile 和试运行结果生成回答。";
            case "difference_diagnosis_layer_1" -> "固定指标、医院、统计周期、文件类型和外部声明值。";
            case "difference_diagnosis_layer_2" -> "对比 Wiki 字段契约、医院映射与 DBHub 实时元数据。";
            case "difference_diagnosis_layer_3" -> "按当前生效口径生成、校验并试运行基准 SQL。";
            case "difference_diagnosis_layer_4" -> "在同医院、同周期和同数据源下试运行最多五个已审批候选口径。";
            case "difference_diagnosis_layer_5" -> "核对双方都有、单边记录、字段值和达标判定差异。";
            case "difference_diagnosis_layer_6" -> "执行 Wiki 允许列表中的空值、重复和时间逻辑检查。";
            case "difference_diagnosis_conclusion" -> "根据已确认 Evidence 给出保守的差异结论。";
            case "difference_diagnosis_answer" -> "服务端按固定报告模板整理回答，不允许模型补造原因。";
            case "dual_period_validation" -> "在访问抽取接口和 DBHub 前强制校验统计区间不超过一个自然月。";
            case "source_extraction_prepare" -> "固定发布版本、规则、Profile、源 SQL 哈希和幂等键。";
            case "source_data_extraction" -> "调用受控抽取网关一次；后续诊断复用本轮抽取回执。";
            case "batch_data_initialization_validation" -> "抽取前核对双库结构，并检查业务源库的数据量、空值和关联覆盖率。";
            case "real_snapshot_data_validation" -> "抽取后核对真实库快照、写入行数及其与业务源查询行数的一致性。";
            case "business_overview" -> "在业务库执行已验证的同一份概览 SQL。";
            case "real_overview" -> "在真实库执行与业务库相同的概览 SQL 和参数。";
            case "dual_comparison" -> "分子和分母必须同时相等；仅比例相等仍属于不一致。";
            case "dual_department_detail" -> "仅在概览不一致时按已验证科室比较键核对集合差异。";
            case "dual_patient_detail" -> "仅在概览不一致时按已验证业务主键核对记录差异。";
            case "dual_diagnosis_conclusion" -> "输出已确认差异统计；契约缺失时不猜测具体原因。";
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
        // key 已先 toLowerCase：驼峰键会变成无分隔符小写（sqlPreview -> sqlpreview），
        // 因此黑名单同时覆盖 snake 与驼峰两种写法，脱敏能力不因改造回退。
        return key.contains("password") || key.contains("secret")
                || List.of("authorization", "api_key", "apikey", "token",
                        "access_token", "accesstoken", "refresh_token", "refreshtoken",
                        "sql", "sql_text", "sqltext", "sql_preview", "sqlpreview",
                        "raw_sql", "rawsql", "generated_sql", "generatedsql",
                        "raw_rows", "rawrows", "rows",
                        "patient_rows", "patientrows").contains(key);
    }

    private static boolean sqlKey(String key) {
        return List.of("sql", "sql_text", "sqltext", "sql_preview", "sqlpreview",
                "raw_sql", "rawsql", "generated_sql", "generatedsql").contains(key);
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

package com.hospital.wikiagent.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.hospital.wikiagent.agent.runtime.AgentRunRequest;
import com.hospital.wikiagent.agent.runtime.CompoundAgentRuntime;
import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.agent.model.PlannerOutputException;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.contract.AgentChatRequest;
import com.hospital.wikiagent.contract.AgentChatResponse;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;

/**
 * 提供 Agent 同步与 SSE 流式对话接口。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentRunController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRunController.class);
    static final long STREAM_TIMEOUT_MILLIS = 1_800_000L;

    private final HospitalAuthService auth;
    private final CompoundAgentRuntime runner;
    private final AgentTraceService traces;
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(4);

    public AgentRunController(
            HospitalAuthService auth, CompoundAgentRuntime runner, AgentTraceService traces) {
        this.auth = auth;
        this.runner = runner;
        this.traces = traces;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            @Valid @RequestBody AgentChatRequest request) {
        HospitalPrincipal principal = auth.authenticate(BearerTokens.require(authorization));
        String traceId = id("TRACE_");
        String resolvedRequestId = requestId == null || requestId.isBlank() ? id("REQ_") : requestId;
        traces.start(traceId, request.sessionId(), principal, request.query());
        try {
            var result = runner.run(
                    runRequest(request, principal, resolvedRequestId, traceId),
                    traces.observer(traceId, event -> { }));
            traces.finish(traceId, result);
            return new AgentChatResponse(
                    result.answer(), result.stopReason(), result.traceId(),
                    result.sessionId(), result.stepCount(), result.clarification());
        } catch (RuntimeException exception) {
            traces.fail(traceId, exception.getMessage());
            LOGGER.error("Java Agent run failed, traceId={}", traceId, exception);
            throw exception;
        }
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            @Valid @RequestBody AgentChatRequest request) {
        HospitalPrincipal principal = auth.authenticate(BearerTokens.require(authorization));
        String traceId = id("TRACE_");
        String resolvedRequestId = requestId == null || requestId.isBlank() ? id("REQ_") : requestId;
        traces.start(traceId, request.sessionId(), principal, request.query());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean streamOpen = new AtomicBoolean(true);
        emitter.onTimeout(() -> streamOpen.set(false));
        emitter.onError(ignored -> streamOpen.set(false));
        emitter.onCompletion(() -> streamOpen.set(false));
        streamExecutor.submit(() -> {
            try {
                var result = runner.run(
                        runRequest(request, principal, resolvedRequestId, traceId),
                        traces.observer(
                                traceId,
                                event -> sendIfOpen(emitter, streamOpen, event)));
                traces.finish(traceId, result);
                completeIfOpen(emitter, streamOpen);
            } catch (RuntimeException exception) {
                String errorId = ApiExceptionHandler.errorId();
                traces.fail(traceId, exception.getMessage());
                LOGGER.error("Java Agent stream failed, errorId={}, traceId={}", errorId, traceId, exception);
                sendIfOpen(emitter, streamOpen, streamError(exception, errorId, traceId));
                completeIfOpen(emitter, streamOpen);
            }
        });
        return emitter;
    }

    private static AgentRunRequest runRequest(
            AgentChatRequest request,
            HospitalPrincipal principal,
            String requestId,
            String traceId) {
        return new AgentRunRequest(
                request.query(), request.sessionId(), request.modelId(), request.fileKey(),
                requestId, traceId, null, "{}", "", principal,
                request.clarificationResponse());
    }

    /**
     * SSE 只是运行进度的通知通道，客户端切换会话、刷新页面或超时不能反向中断
     * 已持久化的批次任务。连接关闭后静默停止推送，后台仍继续计算、收尾并写入会话。
     */
    static void sendIfOpen(
            SseEmitter emitter,
            AtomicBoolean streamOpen,
            Map<String, Object> event) {
        if (!streamOpen.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(String.valueOf(event.getOrDefault("event", "agent_error")))
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException exception) {
            streamOpen.set(false);
            LOGGER.debug("SSE connection closed; background Agent run continues: {}",
                    exception.getMessage());
        }
    }

    static Map<String, Object> streamError(
            RuntimeException exception,
            String errorId,
            String traceId) {
        boolean plannerInvalid = exception instanceof PlannerOutputException;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "agent_error");
        event.put("traceId", traceId);
        event.put("message", plannerInvalid
                ? "模型业务计划格式错误，自动修复后仍未通过校验。请重试；若再次失败，请在“系统设置 → 错误日志”中搜索错误编号 "
                        + errorId
                : "运行失败。请在“系统设置 → 错误日志”中搜索错误编号 " + errorId);
        event.put("errorId", errorId);
        event.put("errorCode", plannerInvalid
                ? ((PlannerOutputException) exception).code() : "RUNTIME_ERROR");
        event.put("stopReason", plannerInvalid
                ? "planner_output_invalid" : "runtime_error");
        event.put("status", "failed");
        return Map.copyOf(event);
    }

    private static void completeIfOpen(
            SseEmitter emitter,
            AtomicBoolean streamOpen) {
        if (streamOpen.compareAndSet(true, false)) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 容器可能已先完成响应；后台运行结果不受影响。
            }
        }
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @PreDestroy
    void close() {
        streamExecutor.shutdownNow();
    }
}

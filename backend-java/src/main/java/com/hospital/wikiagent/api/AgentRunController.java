package com.hospital.wikiagent.api;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.contract.AgentChatRequest;
import com.hospital.wikiagent.contract.AgentChatResponse;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;

@RestController
/** 提供 Agent 同步与 SSE 流式对话接口。 */
@RequestMapping("/api/agent")
public class AgentRunController {
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
                    result.sessionId(), result.stepCount());
        } catch (RuntimeException exception) {
            traces.fail(traceId, exception.getMessage());
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
        SseEmitter emitter = new SseEmitter(300_000L);
        streamExecutor.submit(() -> {
            try {
                var result = runner.run(
                        runRequest(request, principal, resolvedRequestId, traceId),
                        traces.observer(traceId, event -> send(emitter, event)));
                traces.finish(traceId, result);
                emitter.complete();
            } catch (RuntimeException exception) {
                traces.fail(traceId, exception.getMessage());
                try {
                    send(emitter, Map.of(
                            "event", "agent_error",
                            "trace_id", traceId,
                            "message", "Java Agent 运行失败。",
                            "stop_reason", "runtime_error",
                            "status", "failed"));
                    emitter.complete();
                } catch (RuntimeException sendFailure) {
                    emitter.completeWithError(sendFailure);
                }
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
                requestId, traceId, null, "{}", "", principal);
    }

    private static void send(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(String.valueOf(event.getOrDefault("event", "agent_error")))
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new IllegalStateException("SSE 连接已断开", exception);
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

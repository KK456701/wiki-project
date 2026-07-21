package com.hospital.wikiagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

@Component
public class ToolGateway {
    private final ToolRegistry registry;
    private final PolicyDecisionService policy;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Semaphore databaseReads = new Semaphore(2);

    public ToolGateway(
            ToolRegistry registry,
            PolicyDecisionService policy,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.policy = policy;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<ToolResult> execute(
            String toolName,
            Map<String, Object> rawArguments,
            AgentRuntimeContext context,
            AgentRunState state) {
        AgentTool tool;
        try {
            tool = registry.require(toolName);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(ToolResult.failure(
                    "not_found", "TOOL_NOT_FOUND", "工具不可用：" + toolName, false));
        }

        PolicyDecision decision = policy.decide(tool, context, state);
        if (!decision.allowed()) {
            String status = "PERMISSION_DENIED".equals(decision.reasonCode()) ? "forbidden" : "unavailable";
            return CompletableFuture.completedFuture(ToolResult.failure(
                    status, decision.reasonCode(), decision.displayMessage(), false));
        }

        Object input;
        try {
            input = objectMapper.convertValue(rawArguments, tool.inputType());
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(ToolResult.failure(
                    "validation_failed", "INVALID_TOOL_ARGUMENTS", "工具参数不符合约束。", false));
        }

        String fingerprint = fingerprint(toolName, rawArguments);
        int callCount = state.noteToolCall(fingerprint);
        if (callCount > 1) {
            ToolResult cached = state.toolResultCache().get(fingerprint);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached.reused());
            }
            return CompletableFuture.completedFuture(ToolResult.failure(
                    "validation_failed",
                    "AGENT_REPEATED_TOOL_CALL",
                    callCount >= 3
                            ? "工具被重复调用，已停止本次 Agent 循环。"
                            : "该工具已使用相同参数调用过，请根据已有结果选择下一步。",
                    callCount < 3));
        }

        ToolExecutionContext executionContext = new ToolExecutionContext(
                context,
                state.subtaskId(),
                state,
                decision);
        Duration timeout = tool.timeout();
        return CompletableFuture.supplyAsync(
                        () -> invoke(tool, input, executionContext),
                        executor)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(exception -> ToolResult.failure(
                        "timeout",
                        "TOOL_TIMEOUT",
                        "工具执行超时，未获得可用结果。",
                        true))
                .thenApply(result -> {
                    state.toolResultCache().put(fingerprint, result);
                    state.lastToolResults().add(result);
                    return result;
                });
    }

    private ToolResult invoke(AgentTool tool, Object input, ToolExecutionContext context) {
        boolean acquired = false;
        try {
            if (tool.databaseRead()) {
                databaseReads.acquire();
                acquired = true;
            }
            return tool.handler().execute(input, context);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("error", "TOOL_EXECUTION_FAILED", "工具执行被中断。", false);
        } catch (RuntimeException exception) {
            return ToolResult.failure("error", "TOOL_EXECUTION_FAILED", "工具执行失败，内部错误已记录。", false);
        } finally {
            if (acquired) {
                databaseReads.release();
            }
        }
    }

    private String fingerprint(String toolName, Map<String, Object> arguments) {
        try {
            byte[] payload = (toolName + "\n" + objectMapper.writeValueAsString(arguments))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成工具调用指纹", exception);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}

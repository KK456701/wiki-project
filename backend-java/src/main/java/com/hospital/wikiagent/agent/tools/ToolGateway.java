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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.EvidenceRecorder;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具调用的策略执行点：完成权限、类型校验、超时、数据库并发、重复调用、
 * 缓存和 Evidence 记录。任何模型输出都必须经过本类才能触达业务工具。
 *
 * <p>执行顺序固定为“工具注册检查 → 策略判定 → Pydantic 等价的 Jackson 类型转换 →
 * 指纹去重 → 超时/并发控制 → Evidence 记录”。工具成功但证据保存失败时仍视为失败，因为
 * Final Answer 只能消费 VerifiedEvidence，不能直接相信临时工具返回值。</p>
 */
@Component
public class ToolGateway {
    private final ToolRegistry registry;
    private final PolicyDecisionService policy;
    private final ObjectMapper objectMapper;
    private final EvidenceRecorder evidenceRecorder;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Semaphore databaseReads;

    public ToolGateway(
            ToolRegistry registry,
            PolicyDecisionService policy,
            ObjectMapper objectMapper,
            EvidenceRecorder evidenceRecorder) {
        this(registry, policy, objectMapper, evidenceRecorder, 2);
    }

    @Autowired
    public ToolGateway(
            ToolRegistry registry,
            PolicyDecisionService policy,
            ObjectMapper objectMapper,
            EvidenceRecorder evidenceRecorder,
            AgentModelProperties properties) {
        this(registry, policy, objectMapper, evidenceRecorder,
                Math.max(1, properties.getCompoundDbConcurrency()));
    }

    private ToolGateway(
            ToolRegistry registry,
            PolicyDecisionService policy,
            ObjectMapper objectMapper,
            EvidenceRecorder evidenceRecorder,
            int databaseConcurrency) {
        this.registry = registry;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.evidenceRecorder = evidenceRecorder;
        this.databaseReads = new Semaphore(databaseConcurrency);
    }

    ToolGateway(
            ToolRegistry registry,
            PolicyDecisionService policy,
            ObjectMapper objectMapper) {
        this(registry, policy, objectMapper, EvidenceRecorder.noop());
    }

    /**
     * 在当前主体和子任务范围内异步执行一个已注册工具。
     *
     * @param toolName CapabilitySpec 编译得到的工具名，不能直接采用模型文本
     * @param rawArguments 确定性参数编译器产生的参数
     * @param context 已认证医院、请求和数据源上下文
     * @param state 当前子任务状态，用于去重、缓存和 Evidence 关联
     */
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

        // 策略判定必须发生在参数转换和实际工具代码之前，拒绝请求不会触达业务依赖。
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

        // 指纹包含工具名和规范化参数；相同调用只能复用已有结果，不能重复访问数据库。
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
                    ToolResult recorded = result;
                    if (result.ok()) {
                        // 未形成 Evidence 的成功结果不能进入最终回答链路。
                        try {
                            recorded = evidenceRecorder.recordToolResult(
                                    toolName, rawArguments, result, context, state);
                        } catch (RuntimeException exception) {
                            recorded = ToolResult.failure(
                                    "error",
                                    "EVIDENCE_PERSIST_FAILED",
                                    "工具执行成功，但无法保存可验证证据，本轮结果不能用于回答。",
                                    false);
                        }
                    }
                    state.toolResultCache().put(fingerprint, recorded);
                    state.lastToolResults().add(recorded);
                    return recorded;
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
    public void close() {
        executor.shutdownNow();
    }
}

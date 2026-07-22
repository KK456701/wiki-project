package com.hospital.wikiagent.agent.tools;

import java.time.Duration;
import java.util.Set;
import java.util.function.BiPredicate;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;

/**
 * 定义 {@code AgentTool} 的不可变数据载体。
 */
public record AgentTool(
        String name,
        Class<?> inputType,
        Set<String> requiredPermissions,
        Duration timeout,
        RiskLevel riskLevel,
        boolean databaseRead,
        BiPredicate<AgentRuntimeContext, AgentRunState> availability,
        Handler handler) {

    public AgentTool {
        requiredPermissions = requiredPermissions == null ? Set.of() : Set.copyOf(requiredPermissions);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        availability = availability == null ? (context, state) -> true : availability;
    }

    public enum RiskLevel {
        READ_ONLY,
        CONTROLLED_EXECUTION,
        CONTROLLED_WRITE,
        PRIVILEGED
    }

    @FunctionalInterface
    public interface Handler {
        ToolResult execute(Object input, ToolExecutionContext context);
    }
}

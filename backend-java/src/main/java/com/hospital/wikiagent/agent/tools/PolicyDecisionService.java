package com.hospital.wikiagent.agent.tools;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;

@Component
public class PolicyDecisionService {
    public static final String VERSION = "agent-tool-policy-v1";

    public PolicyDecision decide(AgentTool tool, AgentRuntimeContext context, AgentRunState state) {
        if (!context.principal().permissions().containsAll(tool.requiredPermissions())) {
            return new PolicyDecision(
                    Decision.DENY,
                    "PERMISSION_DENIED",
                    "当前用户没有执行该工具所需的权限。",
                    VERSION);
        }
        boolean available;
        try {
            available = tool.availability().test(context, state);
        } catch (RuntimeException exception) {
            available = false;
        }
        if (!available) {
            return new PolicyDecision(
                    Decision.DENY,
                    "TOOL_UNAVAILABLE",
                    "当前运行状态不允许执行该工具。",
                    VERSION);
        }
        return new PolicyDecision(Decision.ALLOW, "POLICY_ALLOWED", "允许执行。", VERSION);
    }
}

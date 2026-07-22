package com.hospital.wikiagent.agent.evidence;

import java.util.Map;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;

/**
 * 定义 {@code EvidenceRecorder} 的稳定协作契约，便于替换实现和隔离测试。
 */
public interface EvidenceRecorder {
    ToolResult recordToolResult(
            String toolName,
            Map<String, Object> arguments,
            ToolResult result,
            AgentRuntimeContext context,
            AgentRunState state);

    static EvidenceRecorder noop() {
        return (toolName, arguments, result, context, state) -> result;
    }
}

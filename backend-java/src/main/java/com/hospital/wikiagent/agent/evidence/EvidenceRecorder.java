package com.hospital.wikiagent.agent.evidence;

import java.util.Map;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;

/**
 * 定义 {@code EvidenceRecorder} 的稳定协作契约，便于替换实现和隔离测试。
 *
 * <p>实现方必须遵守相同的医院隔离、超时和错误语义，替换实现不能扩大权限。接口保持无框架业务语义，便于单元测试和受控适配外部系统。</p>
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

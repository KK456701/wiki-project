package com.hospital.wikiagent.agent.planning;

import java.util.List;

import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.ir.PlanCapability;

/**
 * 定义 {@code ControllerDecision} 的不可变数据载体。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。敏感字段应保存安全引用或摘要，而不是患者级原文。</p>
 */
public record ControllerDecision(
        ControllerAction action,
        PlanCapability capability,
        List<String> toolNames,
        String code,
        String message,
        FallbackCategory fallbackCategory,
        FailureClass failureClass) {

    public ControllerDecision {
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        if (toolNames.size() > 2) {
            throw new IllegalArgumentException("控制器每步最多开放两个工具");
        }
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }

    public enum ControllerAction {
        EXECUTE_TOOL,
        COMPOSE_ANSWER,
        FALLBACK
    }
}

package com.hospital.wikiagent.agent.planning;

import java.util.List;

import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.ir.PlanCapability;

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

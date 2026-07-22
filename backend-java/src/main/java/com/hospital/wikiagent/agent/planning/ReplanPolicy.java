package com.hospital.wikiagent.agent.planning;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.runtime.AgentRunState;

/**
 * 只允许一次方向性重规划；运行设施和证据错误必须原地失败。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class ReplanPolicy {
    public static final int MAX_REPLAN_COUNT = 1;
    private static final Set<FailureClass> ALLOWED = Set.of(
            FailureClass.SEMANTIC_PLAN_ERROR,
            FailureClass.TASK_TYPE_ERROR,
            FailureClass.USER_GOAL_CHANGED,
            FailureClass.ALTERNATIVE_DIRECTION_AVAILABLE);

    public boolean canReplan(AgentRunState state, String failureCode) {
        return state.replanCount() < MAX_REPLAN_COUNT
                && ALLOWED.contains(FailureClass.classify(failureCode));
    }

    public void recordFailure(AgentRunState state, String planId) {
        state.incrementReplanCount();
        state.failedPlanIds().add(planId);
    }

    public boolean accepts(AgentRunState state, String planId) {
        return !state.failedPlanIds().contains(planId);
    }
}

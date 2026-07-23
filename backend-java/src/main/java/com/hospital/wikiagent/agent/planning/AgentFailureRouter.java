package com.hospital.wikiagent.agent.planning;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.FailureClass;
import com.hospital.wikiagent.agent.runtime.AgentRunState;

/**
 * 统一决定一次 Agent 失败应进入重规划还是直接兜底。
 *
 * <p>计划校验、工具参数编译和工具执行都必须经过这里，避免不同阶段各自维护一套
 * Replan 判断。只有方向性语义错误允许重规划一次；缺时间、权限、数据库、对象过期、
 * Evidence 冲突和普通工具故障必须保留原始失败并进入兜底。</p>
 */
@Component
public class AgentFailureRouter {
    private final ReplanPolicy replanPolicy;

    public AgentFailureRouter(ReplanPolicy replanPolicy) {
        this.replanPolicy = replanPolicy;
    }

    /**
     * 根据统一失败分类给出路由结果，不在此调用模型或修改运行状态。
     */
    public FailureRoute route(AgentRunState state, String failureCode) {
        FailureClass failureClass = FailureClass.classify(failureCode);
        FailureAction action = replanPolicy.canReplan(state, failureCode)
                ? FailureAction.REPLAN
                : FailureAction.FALLBACK;
        return new FailureRoute(action, failureClass, failureCode == null ? "" : failureCode);
    }

    /**
     * 只有真正准备调用 Replanner 时才登记次数和失败计划，避免普通兜底消耗重规划额度。
     */
    public void recordReplan(AgentRunState state, String planId) {
        replanPolicy.recordFailure(state, planId);
    }

    public boolean acceptsAlternative(AgentRunState state, String planId) {
        return replanPolicy.accepts(state, planId);
    }

    public enum FailureAction {
        REPLAN,
        FALLBACK
    }

    public record FailureRoute(
            FailureAction action,
            FailureClass failureClass,
            String failureCode) {

        public boolean shouldReplan() {
            return action == FailureAction.REPLAN;
        }
    }
}

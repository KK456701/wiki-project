from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Callable

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext

from .compiler import PlanCompiler
from .contracts import CompiledPlan, RequestPlan
from .controller import AgentStateController, ControllerDecision
from .planner import RequestPlanner
from .validator import PlanValidation, PlanValidator
from .verifier import PlanVerifier, VerificationResult
from .replan import ReplanPolicy


@dataclass(frozen=True, slots=True)
class PlanningExecution:
    request_plan: RequestPlan
    compiled_plan: CompiledPlan
    validation: PlanValidation


class AgentPlanningRuntime:
    def __init__(
        self,
        *,
        planner: RequestPlanner,
        compiler: PlanCompiler | None = None,
        validator: PlanValidator | None = None,
        controller: AgentStateController | None = None,
        verifier: PlanVerifier | None = None,
        now_provider: Callable[[], datetime] = datetime.now,
        replan_policy: ReplanPolicy | None = None,
    ) -> None:
        self.planner = planner
        self.compiler = compiler or PlanCompiler()
        self.validator = validator or PlanValidator()
        self.controller = controller or AgentStateController()
        self.verifier = verifier or PlanVerifier()
        self.now_provider = now_provider
        self.replan_policy = replan_policy or ReplanPolicy(max_replan_count=1)

    async def prepare(
        self,
        query: str,
        context: AgentRuntimeContext,
        state: AgentRunState,
    ) -> PlanningExecution:
        now = self.now_provider()
        request_plan = await self.planner.plan(
            query=query,
            context=context,
            state=state,
            now=now,
        )
        request_plan = request_plan.model_copy(deep=True)
        if not request_plan.target_indicator.rule_id and state.current_rule_id:
            request_plan.target_indicator.rule_id = state.current_rule_id
        if (
            not request_plan.time_expression.start_time
            and not request_plan.time_expression.end_time
            and state.current_stat_start
            and state.current_stat_end
            and not request_plan.time_expression.raw_text
        ):
            request_plan.time_expression.start_time = state.current_stat_start
            request_plan.time_expression.end_time = state.current_stat_end
        return PlanningExecution(
            request_plan=request_plan,
            compiled_plan=self.compiler.compile(request_plan),
            validation=self.validator.validate(request_plan, now=now),
        )

    def next_decision(
        self,
        execution: PlanningExecution,
        state: AgentRunState,
    ) -> ControllerDecision:
        return self.controller.next_decision(
            execution.compiled_plan,
            execution.validation,
            state,
        )

    async def try_replan(
        self,
        execution: PlanningExecution,
        *,
        query: str,
        context: AgentRuntimeContext,
        state: AgentRunState,
        failure_code: str,
        failure_reason: str,
    ) -> PlanningExecution | None:
        if not self.replan_policy.can_replan(state, failure_code):
            return None
        self.replan_policy.record_failure(
            state, execution.compiled_plan.plan_id
        )
        now = self.now_provider()
        replan = getattr(self.planner, "replan", None)
        if callable(replan):
            request_plan = await replan(
                query=query,
                context=context,
                state=state,
                now=now,
                original_plan=execution.request_plan,
                failure_code=failure_code,
                failure_reason=failure_reason,
            )
        else:
            request_plan = await self.planner.plan(
                query=query,
                context=context,
                state=state,
                now=now,
            )
        compiled = self.compiler.compile(request_plan)
        if not self.replan_policy.accept_plan(state, compiled.plan_id):
            return None
        return PlanningExecution(
            request_plan=request_plan,
            compiled_plan=compiled,
            validation=self.validator.validate(request_plan, now=now),
        )

    def verify(
        self,
        execution: PlanningExecution,
        state: AgentRunState,
        context: AgentRuntimeContext,
    ) -> VerificationResult:
        return self.verifier.verify(
            execution.compiled_plan,
            state,
            context,
            expected_time=execution.validation.resolved_time,
        )

    @staticmethod
    def instruction(
        execution: PlanningExecution,
        decision: ControllerDecision,
        state: AgentRunState,
    ) -> str:
        target = execution.request_plan.target_indicator
        period = execution.validation.resolved_time
        values = [
            "当前请求已由服务端计划控制器约束。",
            f"当前业务能力：{decision.capability.value if decision.capability else 'none'}。",
            f"本步只允许调用：{', '.join(decision.tool_names) if decision.tool_names else '无工具，直接回答'}。",
        ]
        if target.raw_name:
            values.append(f"目标指标原文：{target.raw_name}。")
        rule_id = state.current_rule_id or target.rule_id
        if rule_id:
            values.append(f"当前 rule_id：{rule_id}。")
        if period is not None:
            values.append(
                "统计区间："
                f"{period.start_time.isoformat()} 至 {period.end_time.isoformat()}，"
                "左闭右开。"
            )
        values.append("不得调用未展示工具，不得自行增加或跳过业务步骤。")
        return "\n".join(values)

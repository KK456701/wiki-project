from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
import re
import time
from typing import Callable

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext

from .compiler import PlanCompiler
from .contracts import (
    CompiledPlan,
    PlanIntent,
    RequestPlan,
    RequestedOutput,
    TimeExpression,
)
from .controller import AgentStateController, ControllerDecision
from .planner import RequestPlanner
from .validator import PlanValidation, PlanValidator
from .verifier import PlanVerifier, VerificationResult
from .replan import ReplanPolicy
from .capability_registry import CapabilitySpecRegistry, get_capability_registry


@dataclass(frozen=True, slots=True)
class PlanningExecution:
    request_plan: RequestPlan
    compiled_plan: CompiledPlan
    validation: PlanValidation
    capability_registry: CapabilitySpecRegistry = field(
        default_factory=get_capability_registry,
        repr=False,
        compare=False,
    )


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
        event_callback=None,
    ) -> None:
        self.planner = planner
        self.compiler = compiler or PlanCompiler()
        self.validator = validator or PlanValidator()
        self.controller = controller or AgentStateController()
        self.verifier = verifier or PlanVerifier()
        self.now_provider = now_provider
        self.replan_policy = replan_policy or ReplanPolicy(max_replan_count=1)
        self.event_callback = event_callback

    def _trace(self, **payload) -> None:
        if self.event_callback is None:
            return
        try:
            self.event_callback({"event": "trace_node", **payload})
        except Exception:
            return

    @staticmethod
    def _query_mentions_time(query: str) -> bool:
        compact = re.sub(r"\s+", "", str(query or ""))
        return bool(re.search(
            r"(?:\d{4}[-年]\d{1,2}|\d{1,2}月|[一二三四五六七八九十]{1,3}月份?"
            r"|本月|这个月|上月|上个月|今年|至今|到现在|开始时间|结束时间"
            r"|统计时间|统计周期|时间范围)",
            compact,
        ))

    @staticmethod
    def _query_requests_upload_comparison(query: str) -> bool:
        compact = re.sub(r"\s+", "", str(query or "")).lower()
        comparison_terms = (
            "不一样",
            "不一致",
            "不相同",
            "有哪些不同",
            "哪些不同",
            "差异",
            "对比",
            "比较",
            "为什么不同",
        )
        if not any(term in compact for term in comparison_terms):
            return False
        if any(term in compact for term in ("文件", "上传", "excel", "表格")):
            return True
        return any(term in compact for term in (
            "我们的结果",
            "两边结果",
            "两个结果",
            "结果不一样",
            "结果不一致",
            "结果不相同",
            "哪些数据",
            "怎么不一样",
        ))

    def _normalize_upload_comparison(
        self,
        request_plan: RequestPlan,
        *,
        query: str,
        state: AgentRunState,
    ) -> None:
        """上传后询问结果差异时，优先执行文件与系统结果对比。"""
        if (
            not state.current_upload_file_key
            or not self._query_requests_upload_comparison(query)
        ):
            return
        request_plan.intent = PlanIntent.INDICATOR_TRIAL_RUN
        request_plan.goal = "对比刚上传的指标文件与本院系统结果并解释差异"
        request_plan.requested_outputs = [
            output
            for output in request_plan.requested_outputs
            if output is not RequestedOutput.DIAGNOSIS
        ]
        for output in (
            RequestedOutput.FILE_ANALYSIS,
            RequestedOutput.TRIAL_RESULT,
        ):
            if output not in request_plan.requested_outputs:
                request_plan.requested_outputs.append(output)

    def _normalize_time_expression(
        self,
        request_plan: RequestPlan,
        *,
        query: str,
        state: AgentRunState,
        now: datetime,
    ) -> None:
        outputs = set(request_plan.requested_outputs)
        needs_time = bool(outputs & {
            RequestedOutput.PREPARED_SQL_HANDLE,
            RequestedOutput.TRIAL_RESULT,
            RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT,
        }) or request_plan.intent in {
            PlanIntent.INDICATOR_SQL_PREPARE,
            PlanIntent.INDICATOR_TRIAL_RUN,
            PlanIntent.IMPLEMENTATION_VALIDATION,
        }
        if not needs_time:
            return

        user_expression = TimeExpression(raw_text=query)
        resolved_user_time = self.validator.resolver.resolve(
            user_expression,
            now=now,
        )
        if resolved_user_time is not None:
            request_plan.time_expression = TimeExpression(
                raw_text=query,
                start_time=resolved_user_time.start_time.isoformat(),
                end_time=resolved_user_time.end_time.isoformat(),
            )
            return

        if (
            state.current_stat_start
            and state.current_stat_end
            and not self._query_mentions_time(query)
        ):
            request_plan.time_expression = TimeExpression(
                raw_text="复用当前已确认统计周期",
                start_time=state.current_stat_start,
                end_time=state.current_stat_end,
            )
            return

        request_plan.time_expression = TimeExpression(raw_text=query)

    def _normalize_concrete_result_request(
        self,
        request_plan: RequestPlan,
        *,
        query: str,
        now: datetime,
    ) -> None:
        """将“怎么算 + 明确统计周期”确定性纠偏为指标结果试运行。"""
        if request_plan.intent is not PlanIntent.RULE_EXPLANATION:
            return
        outputs = set(request_plan.requested_outputs)
        if RequestedOutput.PREPARED_SQL_HANDLE in outputs:
            return
        compact = re.sub(r"\s+", "", str(query or ""))
        if any(term in compact for term in (
            "只解释",
            "只看公式",
            "只要公式",
            "不查数据库",
            "不执行数据库",
        )):
            return
        if not any(term in compact for term in (
            "怎么算",
            "如何计算",
            "算一下",
            "计算一下",
            "统计一下",
            "具体结果",
            "指标结果",
            "结果是多少",
            "是多少",
            "多少",
            "试运行",
        )):
            return
        resolved = self.validator.resolver.resolve(
            TimeExpression(raw_text=query),
            now=now,
        )
        if resolved is None:
            return
        request_plan.intent = PlanIntent.INDICATOR_TRIAL_RUN
        request_plan.goal = "按用户指定统计周期计算当前指标的本院实际结果"
        if RequestedOutput.TRIAL_RESULT not in request_plan.requested_outputs:
            request_plan.requested_outputs.append(RequestedOutput.TRIAL_RESULT)

    @staticmethod
    def _query_requests_implementation_validation(query: str) -> bool:
        compact = re.sub(r"\s+", "", str(query or ""))
        return any(term in compact for term in (
            "全面实施验收",
            "全面实施验证",
            "上线验收",
            "迁移核对",
            "全链路实施验证",
            "全链路验收",
        ))

    def _normalize_implementation_validation(
        self,
        request_plan: RequestPlan,
        *,
        query: str,
    ) -> None:
        """明确验收用语由服务端固定为专用意图，避免小模型退化为普通诊断。"""
        if not self._query_requests_implementation_validation(query):
            return
        request_plan.intent = PlanIntent.IMPLEMENTATION_VALIDATION
        request_plan.goal = "对当前指标执行全面实施验收并生成结构化验收结论"
        request_plan.requested_outputs = [
            RequestedOutput.IMPLEMENTATION_VALIDATION_REPORT
        ]

    async def prepare(
        self,
        query: str,
        context: AgentRuntimeContext,
        state: AgentRunState,
        *,
        forced_time_range: tuple[str, str] | None = None,
        request_plan_override: RequestPlan | None = None,
    ) -> PlanningExecution:
        now = self.now_provider()
        if request_plan_override is not None:
            request_plan = request_plan_override
        else:
            request_plan = await self.planner.plan(
                query=query,
                context=context,
                state=state,
                now=now,
            )
        request_plan = request_plan.model_copy(deep=True)
        if (
            not request_plan.target_indicator.rule_id
            and not request_plan.target_indicator.raw_name
            and state.current_rule_id
        ):
            request_plan.target_indicator.rule_id = state.current_rule_id
        elif (
            request_plan.target_indicator.raw_name
            and not request_plan.target_indicator.rule_id
        ) or (
            request_plan.target_indicator.rule_id
            and state.current_rule_id
            and request_plan.target_indicator.rule_id != state.current_rule_id
        ):
            state.current_rule_id = None
            state.validated_sql_ids = []
            stale_facts = {
                "rule_identity",
                "definition",
                "formula",
                "effective_level",
                "implementation_status",
                "sql_object",
                "sql_validation",
                "trial_run",
                "aggregate_result",
            }
            state.evidence = [
                item
                for item in state.evidence
                if not stale_facts.intersection(item.get("fact_types") or [])
            ]
        self._normalize_upload_comparison(
            request_plan,
            query=query,
            state=state,
        )
        self._normalize_implementation_validation(
            request_plan,
            query=query,
        )
        self._normalize_concrete_result_request(
            request_plan,
            query=query,
            now=now,
        )
        if forced_time_range is not None:
            request_plan.time_expression = TimeExpression(
                raw_text="服务端统一统计周期",
                start_time=forced_time_range[0],
                end_time=forced_time_range[1],
            )
        else:
            self._normalize_time_expression(
                request_plan,
                query=query,
                state=state,
                now=now,
            )
        compile_started = time.perf_counter()
        compiled = self.compiler.compile(request_plan)
        self._trace(
            node_name="plan_compile",
            node_type="code",
            status="success",
            duration_ms=max(1, int((time.perf_counter() - compile_started) * 1000)),
            input_data={"request_plan": request_plan.model_dump(mode="json")},
            output_data={"compiled_plan": compiled.model_dump(mode="json")},
            processing_data={
                "description": "把不含工具名的业务计划确定性编译为能力节点和必需事实。"
            },
            config_data={
                "compiler": type(self.compiler).__name__,
                "ir_version": compiled.schema_version,
                "request_plan_version": compiled.request_plan_version,
                "capability_registry_version": compiled.capability_registry_version,
                "prompt_version": compiled.prompt_version,
                "model_adapter_version": compiled.model_adapter_version,
                "verifier_version": compiled.verifier_version,
            },
        )
        validate_started = time.perf_counter()
        validation = self.validator.validate(request_plan, now=now)
        self._trace(
            node_name="plan_validate",
            node_type="code",
            status="success" if validation.ok else "failed",
            duration_ms=max(1, int((time.perf_counter() - validate_started) * 1000)),
            input_data={
                "request_plan": request_plan.model_dump(mode="json"),
                "current_time": now.isoformat(),
            },
            output_data={"validation": validation.model_dump(mode="json")},
            processing_data={
                "description": "校验目标冲突、权限约束和统计时间，并注入确定性兜底类别。"
            },
            config_data={"validator": type(self.validator).__name__},
        )
        return PlanningExecution(
            request_plan=request_plan,
            compiled_plan=compiled,
            validation=validation,
            capability_registry=self.compiler.registry,
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
        self._trace(
            node_name="plan_replan",
            node_type="code",
            status="success",
            duration_ms=1,
            input_data={
                "original_plan_id": execution.compiled_plan.plan_id,
                "failure_code": failure_code,
                "failure_reason": failure_reason,
                "known_facts": state.evidence_ids,
            },
            output_data={
                "compiled_plan": compiled.model_dump(mode="json"),
                "replan_count": state.replan_count,
            },
            processing_data={
                "description": "仅在允许的语义方向错误下接受一次替代计划，并保留失败路径。"
            },
            config_data={"max_replan_count": self.replan_policy.max_replan_count},
            failure_class=self.replan_policy.classify(failure_code).value,
        )
        return PlanningExecution(
            request_plan=request_plan,
            compiled_plan=compiled,
            validation=self.validator.validate(request_plan, now=now),
            capability_registry=self.compiler.registry,
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
        from app.prompts import format_prompt

        target_line = f"目标指标原文：{target.raw_name}。" if target.raw_name else ""
        rule_id = state.current_rule_id or target.rule_id
        rule_line = f"当前 rule_id：{rule_id}。" if rule_id else ""
        period_line = ""
        if period is not None:
            period_line = (
                "统计区间："
                f"{period.start_time.isoformat()} 至 {period.end_time.isoformat()}，"
                "左闭右开。"
            )
        return format_prompt(
            "agent_final_answer_step",
            capability=decision.capability.value if decision.capability else "none",
            tool_names=", ".join(decision.tool_names) if decision.tool_names else "无工具，直接回答",
            target_line=target_line,
            rule_line=rule_line,
            period_line=period_line,
        ).strip()

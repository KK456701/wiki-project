"""模型驱动、工具观察式的最小 Agent 循环。"""

from __future__ import annotations

import asyncio
import base64
from contextvars import ContextVar
import json
import logging
import re
import time

from app.agent_runtime.contracts import (
    AgentModelResponse,
    AgentRunResult,
    AgentRunState,
    AgentRuntimeContext,
)
from app.agent_runtime.events import AgentEventCallback, emit_agent_event
from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.agent_runtime.prompts import (
    AGENT_SYSTEM_PROMPT,
    CHINESE_REQUIRED_PROMPT,
    EMPTY_ANSWER_PROMPT,
    EVIDENCE_REQUIRED_PROMPT,
    TRIAL_RUN_REQUIRED_PROMPT,
    final_answer_correction,
)
from app.agent_runtime.response_guard import (
    contains_tool_protocol_markup,
    evidence_correction_prompt,
    missing_fact_types,
    normalize_agent_answer,
)
from app.agent_planning.dispatch import (
    DeterministicDispatchError,
    build_deterministic_tool_call,
)
from app.agent_planning.contracts import (
    PlanIntent,
    RequestPlan,
    RequestedOutput,
    TargetIndicator,
    TimeExpression,
)
from app.agent_planning.runtime import AgentPlanningRuntime
from app.agent_planning.planner import AgentPlanningError
from app.agent_tools.gateway import ToolGateway
from app.agent_tools.registry import ToolRegistry
from app.prompts import prompt_version


logger = logging.getLogger("wiki_agent.agent_runtime")
_ACTIVE_SUBTASK_ID: ContextVar[str] = ContextVar(
    "agent_active_subtask_id",
    default="root",
)


def _contains_chinese(text: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", text))


_DIAGNOSIS_TERMS = (
    "异常",
    "诊断",
    "排查",
    "算不对",
    "算错",
    "不准确",
    "不准",
    "有问题",
    "问题在哪",
)

_TRIAL_RUN_TERMS = (
    "多少",
    "是多少",
    "统计",
    "统计时间",
    "统计周期",
    "试运行",
    "结果",
    "今年",
    "本月",
    "上月",
    "到现在",
    "从",
    "开始怎么算",
    "算一下",
)

_COMPOUND_INDICATOR_SPLIT = re.compile(
    r"(?:[,，;；]\s*)?(?:还有|以及|另外(?:再|还|也)?|同时(?:还|也)?(?:查询|计算|查看|看看)?)"
)

_INDICATOR_CLAUSE_HINTS = (
    "指标",
    "率",
    "比例",
    "会诊",
    "转科",
    "查房",
    "患者",
    "住院",
    "手术",
    "抢救",
    "死亡",
    "感染",
    "输血",
)

_COMPOUND_FOLLOWUP_REFERENCE = re.compile(
    r"(?:这|上述|上面|前面)(?:两|2|几|多|些)个|这两个|它们|二者|分别"
)


def _classify_request_kind(user_message: str) -> str | None:
    compact = re.sub(r"\s+", "", user_message)
    if any(term in compact for term in _DIAGNOSIS_TERMS):
        return "diagnosis"
    if any(term in compact for term in _TRIAL_RUN_TERMS):
        return "trial_run"
    return None


def _split_compound_indicator_query(user_message: str) -> list[str]:
    """仅拆分带明确并列连接词、且每段都像指标目标的请求。"""
    parts = [
        part.strip(" \t\r\n,，;；。")
        for part in _COMPOUND_INDICATOR_SPLIT.split(str(user_message or ""))
    ]
    parts = [part for part in parts if part]
    if not 2 <= len(parts) <= 3:
        return []
    if not all(any(hint in part for hint in _INDICATOR_CLAUSE_HINTS) for part in parts):
        return []
    return parts


def _compound_indicator_target(clause: str) -> str:
    """从已拆分子句中移除公共时间和结果措辞，保留指标检索词。"""
    target = re.sub(
        r"(?:从|自|在)?(?:\d{2,4}\s*年)?(?:1[0-2]|[1-9])\s*月份?.*$",
        "",
        str(clause or "").strip(),
    ).strip()
    target = re.sub(
        r"(?:的)?(?:具体)?(?:结果|数值|指标值)(?:怎么(?:算|计算)|如何计算|是多少)?$",
        "",
        target,
    ).strip()
    target = re.sub(
        r"^(?:请|帮我|再|同时|查询|查一下|计算|统计|查看|看看)+",
        "",
        target,
    ).strip(" \t\r\n,，;；。？?")
    return target or str(clause or "").strip()


def _compound_result_plan(clause: str) -> RequestPlan:
    target_name = _compound_indicator_target(clause)
    return RequestPlan(
        intent=PlanIntent.INDICATOR_TRIAL_RUN,
        goal=f"查询{target_name}在服务端统一统计周期内的具体结果",
        target_indicator=TargetIndicator(raw_name=target_name),
        requested_outputs=[RequestedOutput.TRIAL_RESULT],
    )


def _compound_followup_kind(
    user_message: str,
    state: AgentRunState,
) -> str | None:
    compact = re.sub(r"\s+", "", str(user_message or "")).lower()
    if len(state.current_rule_ids) < 2 or not _COMPOUND_FOLLOWUP_REFERENCE.search(compact):
        return None
    if "sql" in compact:
        return "sql_prepare"
    if any(term in compact for term in _TRIAL_RUN_TERMS):
        return "trial_run"
    if any(term in compact for term in (
        "公式",
        "定义",
        "口径",
        "怎么算",
        "如何计算",
        "分子",
        "分母",
        "含义",
        "什么意思",
    )):
        return "rule_explanation"
    return None


def _compound_followup_plan(rule_id: str, kind: str) -> RequestPlan:
    if kind == "sql_prepare":
        intent = PlanIntent.INDICATOR_SQL_PREPARE
        outputs = [RequestedOutput.PREPARED_SQL_HANDLE]
        goal = f"生成指标 {rule_id} 在已确认统计周期内的受控 SQL"
    elif kind == "trial_run":
        intent = PlanIntent.INDICATOR_TRIAL_RUN
        outputs = [RequestedOutput.TRIAL_RESULT]
        goal = f"查询指标 {rule_id} 在已确认统计周期内的具体结果"
    else:
        intent = PlanIntent.RULE_EXPLANATION
        outputs = [
            RequestedOutput.DEFINITION,
            RequestedOutput.FORMULA,
            RequestedOutput.EXPLANATION,
        ]
        goal = f"解释指标 {rule_id} 的定义、公式和本院口径"
    return RequestPlan(
        intent=intent,
        goal=goal,
        target_indicator=TargetIndicator(raw_name=rule_id, rule_id=rule_id),
        requested_outputs=outputs,
    )


def _request_kind_from_plan(user_message: str, planning_execution) -> str | None:
    if planning_execution is None:
        return _classify_request_kind(user_message)
    outputs = {
        item.value if hasattr(item, "value") else str(item)
        for item in planning_execution.request_plan.requested_outputs
    }
    if "diagnosis" in outputs:
        return "diagnosis"
    if "implementation_validation_report" in outputs:
        return "trial_run"
    if "trial_result" in outputs:
        return "trial_run"
    return _classify_request_kind(user_message)


def _compose_existing_detail_answer(
    user_message: str,
    state: AgentRunState,
) -> str | None:
    """将分子/分母明细追问绑定到最近一次成功试运行。"""
    compact = re.sub(r"\s+", "", str(user_message or ""))
    targets = [target for target in ("分子", "分母") if target in compact]
    if not targets:
        return None
    asks_for_records = any(
        term in compact
        for term in ("记录", "明细", "名单", "列表")
    ) or bool(re.search(r"(?:分子|分母).{0,8}(?:有哪些|是哪些|具体是哪)", compact))
    if not asks_for_records:
        return None

    run_id = str(state.last_run_id or "").strip()
    if re.fullmatch(r"RUN_[A-Za-z0-9_]+", run_id) is None:
        return "当前会话还没有可查看明细的成功计算结果，请先指定统计时间并计算指标。"

    target_text = "、".join(targets)
    return (
        f"已定位到最近一次计算的{target_text}明细。"
        f"点击下方入口后查看“{target_text}明细”页签：\n\n"
        f"{{{{detail_export:{run_id}}}}}"
    )


def _latest_rule_name(state: AgentRunState) -> str:
    for result in reversed(state.last_tool_results):
        if not isinstance(result, dict) or result.get("ok") is not True:
            continue
        data = result.get("data") or {}
        rule_name = str(data.get("rule_name") or "").strip()
        if rule_name:
            return rule_name
    return ""


def _has_fact_type(state: AgentRunState, fact_type: str) -> bool:
    return any(
        fact_type in (item.get("fact_types") or [])
        for item in state.evidence
        if isinstance(item, dict)
    )


def _compose_prepared_sql_answer(planning_execution, state: AgentRunState) -> str | None:
    outputs = {
        item.value if hasattr(item, "value") else str(item)
        for item in planning_execution.compiled_plan.requested_outputs
    }
    if "prepared_sql_handle" not in outputs or "trial_result" in outputs:
        return None
    prepared = next(
        (
            item
            for item in reversed(state.last_tool_results)
            if isinstance(item, dict)
            and item.get("ok") is True
            and item.get("code") == "SQL_OBJECT_PREPARED"
        ),
        None,
    )
    if prepared is None:
        return None
    data = prepared.get("data") or {}
    sql_preview = str(data.get("sql_preview") or "").strip()
    if not sql_preview:
        return None
    parameters = data.get("parameters") or {}
    parameter_lines = "\n".join(
        f"- `{key}`：`{value}`" for key, value in parameters.items()
    )
    sql_id = str(data.get("sql_id") or "").strip()
    sql_id_line = f"\n- SQL 对象：`{sql_id}`" if sql_id else ""
    target = _latest_rule_name(state) or planning_execution.request_plan.target_indicator.raw_name.strip()
    title = f"下面是“{target}”的已校验 SQL：" if target else "下面是已校验 SQL："
    return (
        f"{title}\n\n```sql\n{sql_preview}\n```\n\n"
        f"统计参数：\n{parameter_lines or '- 无额外参数'}"
        f"{sql_id_line}\n\n该请求只生成并校验 SQL，不会执行数据库。"
    )


def _compose_implementation_validation_answer(
    tool_results: list[dict],
) -> str | None:
    completed = next(
        (
            item
            for item in reversed(tool_results)
            if isinstance(item, dict)
            and item.get("ok") is True
            and item.get("code") == "IMPLEMENTATION_VALIDATION_COMPLETED"
        ),
        None,
    )
    if completed is None:
        return None
    data = completed.get("data") or {}
    status_labels = {
        "passed": "通过",
        "warning": "有警告",
        "failed": "未通过",
        "skipped": "已跳过",
    }
    overall = str(data.get("overall_status") or "failed")
    lines = [
        f"## {data.get('rule_name') or data.get('rule_id') or '当前指标'}实施验收报告",
        "",
        f"- **验收结论**：{status_labels.get(overall, overall)}",
        f"- **报告编号**：`{data.get('report_id') or '-'}`",
        f"- **指标编号**：`{data.get('rule_id') or '-'}`",
        f"- **统计区间**：{data.get('stat_start') or '-'} 至 {data.get('stat_end') or '-'}（左闭右开）",
        "",
        "| 阶段 | 检查项 | 状态 | 结论 |",
        "|---|---|---|---|",
    ]
    for stage in data.get("stages") or []:
        if not isinstance(stage, dict):
            continue
        status = str(stage.get("status") or "")
        lines.append(
            f"| {stage.get('stage_id') or '-'} | {stage.get('stage_name') or '-'} "
            f"| {status_labels.get(status, status)} | {stage.get('summary') or '-'} |"
        )
    if data.get("run_id"):
        lines.extend((
            "",
            "### L5 试运行结果",
            "",
            f"- 分子：{data.get('numerator_count')}",
            f"- 分母：{data.get('denominator_count')}",
            f"- 指标值：{data.get('result_value')}%",
            f"- SQL 对象：`{data.get('sql_id') or '-'}`",
            f"- 试运行对象：`{data.get('run_id')}`",
        ))
    findings = []
    for stage in data.get("stages") or []:
        if not isinstance(stage, dict) or stage.get("status") not in {"warning", "failed"}:
            continue
        codes = "、".join(
            f"`{code}`" for code in stage.get("finding_codes") or []
        )
        findings.append(
            f"- {stage.get('stage_id')} {stage.get('stage_name')}："
            f"{stage.get('summary')}" + (f"（{codes}）" if codes else "")
        )
    if findings:
        lines.extend(("", "### 待处理项", "", *findings))
    lines.extend((
        "",
        "> 本报告由服务端固定工作流生成；L1、L4、L5 和可选 L6 的状态均来自本轮工具证据，不由模型推测。",
    ))
    return "\n".join(lines)


def _compose_rule_components_answer(
    user_message: str,
    tool_results: list[dict],
) -> str | None:
    compact = re.sub(r"\s+", "", str(user_message or ""))
    if not any(term in compact for term in ("分子", "分母")):
        return None
    effective = next(
        (
            item
            for item in reversed(tool_results)
            if isinstance(item, dict)
            and item.get("ok") is True
            and item.get("code") == "EFFECTIVE_RULE_FOUND"
        ),
        None,
    )
    if effective is None:
        return None
    data = effective.get("data") or {}
    rule_name = str(data.get("rule_name") or data.get("rule_id") or "当前指标")
    numerator = str(data.get("numerator_rule") or "未配置")
    denominator = str(data.get("denominator_rule") or "未配置")
    filter_rule = str(data.get("filter_rule") or "").strip()
    exclude_rule = str(data.get("exclude_rule") or "").strip()
    lines = [
        f"{rule_name}的分子和分母含义如下：",
        "",
        f"- **分子**：{numerator}",
        f"- **分母**：{denominator}",
        "- **计算公式**：指标率 = 分子 ÷ 分母 × 100%",
    ]
    if filter_rule:
        lines.append(f"- **统计范围**：{filter_rule}")
    if exclude_rule:
        lines.append(f"- **排除规则**：{exclude_rule}")
    lines.extend(("", "以上内容仅来自本轮读取的本院生效规则。"))
    return "\n".join(lines)


def _display_number(value) -> str:
    number = float(value)
    if number.is_integer():
        return str(int(number))
    return f"{number:.4f}".rstrip("0").rstrip(".")


def _compose_upload_comparison_answer(tool_results: list[dict]) -> str | None:
    """基于汇总或逐条对比证据生成回答，避免模型补充无证据原因。"""
    analyzed = next(
        (
            result
            for result in reversed(tool_results)
            if isinstance(result, dict)
            and result.get("ok") is True
            and result.get("code") == "UPLOAD_ANALYZED"
            and (
                isinstance((result.get("data") or {}).get("aggregate_comparison"), dict)
                or isinstance((result.get("data") or {}).get("row_comparison"), dict)
            )
        ),
        None,
    )
    if analyzed is None:
        return None
    data = analyzed.get("data") or {}
    row_comparison = data.get("row_comparison") or {}
    if row_comparison:
        status = row_comparison.get("comparison_status")
        if status != "row_level_compared":
            message = str(row_comparison.get("message") or "当前文件不能进行逐条对比。")
            details = []
            if row_comparison.get("system_rule_id"):
                details.append(
                    f"- 当前系统指标：{row_comparison.get('system_rule_name') or '-'} "
                    f"(`{row_comparison.get('system_rule_id')}`)"
                )
            if row_comparison.get("uploaded_rule_id"):
                details.append(
                    f"- 上传文件指标：{row_comparison.get('uploaded_rule_name') or '-'} "
                    f"(`{row_comparison.get('uploaded_rule_id')}`)"
                )
            return "\n".join([message, "", *details]).rstrip()

        lines = [
            "已完成上传明细与系统明细的逐条核对：",
            "",
            "| 分类 | 记录数 |",
            "|---|---:|",
            f"| 双方都有 | {row_comparison.get('both_count', 0)} |",
            f"| 仅系统有 | {row_comparison.get('system_only_count', 0)} |",
            f"| 仅上传文件有 | {row_comparison.get('uploaded_only_count', 0)} |",
            f"| 同一记录但字段值不同 | {row_comparison.get('field_difference_count', 0)} |",
            "",
            "逐条匹配字段："
            + "、".join(
                f"`{field}`" for field in row_comparison.get("matching_fields") or []
            )
            + "。",
        ]
        findings = row_comparison.get("confirmed_findings") or []
        if findings:
            lines.extend(["", "已确认的差异证据："])
            lines.extend(f"- {finding}" for finding in findings)
        lines.extend([
            "",
            "患者级原始值不会发送给模型；完整记录请通过下方受控差异 Excel 查看。",
        ])
        return "\n".join(lines)

    comparison = data.get("aggregate_comparison") or {}
    metrics = comparison.get("metrics") or []
    if not metrics:
        return None

    lines = [
        "上传文件与系统结果存在以下汇总差异：",
        "",
        "| 项目 | 系统值 | 文件值 | 文件值 - 系统值 | 结论 |",
        "|---|---:|---:|---:|---|",
    ]
    for item in metrics:
        unit = str(item.get("unit") or "")
        lines.append(
            "| {metric} | {system}{unit} | {uploaded}{unit} | {difference}{unit} | {status} |".format(
                metric=item.get("metric") or item.get("role") or "指标",
                system=_display_number(item.get("system_value")),
                uploaded=_display_number(item.get("uploaded_value")),
                difference=_display_number(item.get("difference")),
                unit=unit,
                status="一致" if item.get("match") else "不一致",
            )
        )

    headers = [
        str(header)
        for sheet in (data.get("sheets") or [])
        for header in (sheet.get("headers") or [])
        if str(header).strip()
    ]
    lines.extend([
        "",
        "目前只能确认以上数值不同，不能确认造成差异的具体原因。",
        "",
        comparison.get("cause_analysis_note")
        or "上传文件没有逐条业务记录，因此不能判断重复、统计周期或 ICU 过滤等原因。",
    ])
    if headers:
        lines.append(f"当前文件可见字段：{ '、'.join(dict.fromkeys(headers)) }。")
    required = comparison.get("required_fields_for_cause_analysis") or []
    if required:
        lines.append(
            "若要定位到具体差异记录，请提供至少包含以下字段的逐条明细："
            + "、".join(f"`{field}`" for field in required)
            + "。"
        )
    return "\n".join(lines)


def _asks_for_period_clarification(answer: str) -> bool:
    return bool(
        re.search(
            r"(?:请|需要|先|可以|能否).{0,20}"
            r"(?:提供|明确|选择|告诉|告知|指定|确认).{0,20}"
            r"(?:统计周期|统计时间|时间范围|起止时间|开始时间|结束时间"
            r"|时间段|起止日期|日期范围|哪个.{0,4}(?:时间|日期|段)"
            r"|查询.{0,4}(?:时间|日期|段))",
            answer,
        )
    )


def _append_trial_detail_export(
    answer: str,
    tool_results: list[dict],
) -> str:
    """为本轮成功试运行追加受控明细入口，不依赖模型生成 UI 标记。"""
    safe_answer = re.sub(
        r"\{\{(?:detail(?:_export)?|upload_comparison_export):[^{}\r\n]*\}\}",
        "",
        answer,
    ).rstrip()
    comparison = next(
        (
            result
            for result in reversed(tool_results)
            if isinstance(result, dict)
            and result.get("ok") is True
            and result.get("code") == "UPLOAD_ANALYZED"
            and (
                isinstance((result.get("data") or {}).get("aggregate_comparison"), dict)
                or (
                    isinstance((result.get("data") or {}).get("row_comparison"), dict)
                    and (result.get("data") or {}).get("row_comparison", {}).get(
                        "comparison_status"
                    ) == "row_level_compared"
                )
            )
            and (result.get("data") or {}).get("file_key")
        ),
        None,
    )
    has_upload_analysis = any(
        isinstance(result, dict)
        and result.get("ok") is True
        and result.get("code") == "UPLOAD_ANALYZED"
        for result in tool_results
    )
    for result in reversed(tool_results):
        if (
            not isinstance(result, dict)
            or result.get("ok") is not True
            or result.get("code") != "TRIAL_RUN_COMPLETED"
        ):
            continue
        data = result.get("data") or {}
        run_id = str(data.get("run_id") or "")
        if (
            data.get("status") != "success"
            or re.fullmatch(r"RUN_[A-Za-z0-9_]+", run_id) is None
        ):
            return safe_answer
        if comparison is not None:
            file_key = str((comparison.get("data") or {}).get("file_key") or "")
            file_token = base64.urlsafe_b64encode(file_key.encode("utf-8")).decode("ascii").rstrip("=")
            marker = f"{{{{upload_comparison_export:{run_id}:{file_token}}}}}"
            row_level = (
                ((comparison.get("data") or {}).get("row_comparison") or {}).get(
                    "comparison_status"
                )
                == "row_level_compared"
            )
            return (
                safe_answer
                + "\n\n---\n\n"
                + (
                    "本次对比支持导出双方都有、仅系统有、仅上传文件有的逐条差异表：\n\n"
                    if row_level
                    else "本次对比支持导出文件与系统的汇总差异表：\n\n"
                )
                + marker
            )
        if has_upload_analysis:
            return safe_answer
        marker = f"{{{{detail_export:{run_id}}}}}"
        return (
            safe_answer
            + "\n\n---\n\n"
            + "本次统计支持查看分子、分母明细并导出 Excel：\n\n"
            + marker
        )
    return safe_answer


def _verified_final_messages(
    messages: list[dict[str, Any]],
    verified_evidence_ids: list[str],
) -> list[dict[str, Any]]:
    """Expose successful tool payloads to Final Answer only after verification."""
    verified = set(verified_evidence_ids)
    result: list[dict[str, Any]] = []
    for message in messages:
        if message.get("role") != "tool":
            result.append(message)
            continue
        try:
            payload = json.loads(str(message.get("content") or "{}"))
        except (TypeError, ValueError, json.JSONDecodeError):
            continue
        if payload.get("ok") is not True:
            continue
        evidence_ids = {
            str(value)
            for value in payload.get("evidence_ids") or []
            if str(value)
        }
        if evidence_ids and evidence_ids.issubset(verified):
            result.append(message)
    return result


class AgentRunner:
    def __init__(
        self,
        adapter: AgentModelAdapter,
        registry: ToolRegistry,
        gateway: ToolGateway,
        *,
        max_steps: int = 8,
        max_tool_calls_per_step: int = 3,
        request_timeout_seconds: float = 120.0,
        event_callback: AgentEventCallback | None = None,
        trace_callback: AgentEventCallback | None = None,
        planning_runtime: AgentPlanningRuntime | None = None,
        compound_concurrency: int = 1,
        model_provider: str = "ollama",
    ) -> None:
        self.adapter = adapter
        self.registry = registry
        self.gateway = gateway
        self.max_steps = max_steps
        self.max_tool_calls_per_step = max_tool_calls_per_step
        self.request_timeout_seconds = request_timeout_seconds
        self.event_callback = event_callback
        self.trace_callback = trace_callback
        self.planning_runtime = planning_runtime
        self.compound_concurrency = max(1, int(compound_concurrency))
        self.model_provider = model_provider
        self.compound_semaphore = asyncio.Semaphore(self.compound_concurrency)

    def _trace(self, **payload) -> None:
        if self.trace_callback is None:
            return
        try:
            payload.setdefault("subtask_id", _ACTIVE_SUBTASK_ID.get())
            self.trace_callback({"event": "trace_node", **payload})
        except Exception:
            return

    @staticmethod
    def _compound_subtask_state(parent: AgentRunState) -> AgentRunState:
        return AgentRunState(
            messages=[
                dict(message)
                for message in parent.messages
                if message.get("role") == "system"
            ],
            recent_history=parent.recent_history,
            current_stat_start=parent.current_stat_start,
            current_stat_end=parent.current_stat_end,
            current_upload_file_key=parent.current_upload_file_key,
        )

    @staticmethod
    def _merge_compound_subtask_state(
        parent: AgentRunState,
        child: AgentRunState,
    ) -> None:
        parent.step_count += child.step_count
        parent.messages = child.messages
        parent.evidence.extend(child.evidence)
        parent.evidence_ids.extend(
            value for value in child.evidence_ids if value not in parent.evidence_ids
        )
        parent.verified_evidence_ids.extend(
            value
            for value in child.verified_evidence_ids
            if value not in parent.verified_evidence_ids
        )
        parent.last_tool_results.extend(child.last_tool_results)
        parent.tool_result_cache.update(child.tool_result_cache)
        for fingerprint, count in child.tool_call_counts.items():
            parent.tool_call_counts[fingerprint] = (
                parent.tool_call_counts.get(fingerprint, 0) + count
            )
        for sql_id in child.validated_sql_ids:
            if sql_id not in parent.validated_sql_ids:
                parent.validated_sql_ids.append(sql_id)
        if child.current_rule_id:
            parent.current_rule_id = child.current_rule_id
        if child.current_stat_start:
            parent.current_stat_start = child.current_stat_start
        if child.current_stat_end:
            parent.current_stat_end = child.current_stat_end
        if child.last_run_id:
            parent.last_run_id = child.last_run_id
        if child.last_diagnosis_id:
            parent.last_diagnosis_id = child.last_diagnosis_id
        if child.last_draft_id:
            parent.last_draft_id = child.last_draft_id
        parent.current_request_kind = child.current_request_kind
        parent.replan_count += child.replan_count
        parent.failed_plan_fingerprints.extend(child.failed_plan_fingerprints)
        parent.fallback_category = child.fallback_category
        parent.failure_code = child.failure_code

    async def _run_compound_request(
        self,
        user_message: str,
        context: AgentRuntimeContext,
        run_state: AgentRunState,
    ) -> AgentRunResult | None:
        subqueries = _split_compound_indicator_query(user_message)
        followup_kind = _compound_followup_kind(user_message, run_state)
        followup_rule_ids = (
            list(run_state.current_rule_ids)
            if not subqueries and followup_kind is not None
            else []
        )
        if followup_rule_ids:
            followup_label = {
                "sql_prepare": "SQL",
                "trial_run": "结果",
                "rule_explanation": "分子分母含义",
            }.get(str(followup_kind), "说明")
            subqueries = [
                f"指标 {rule_id} 的{followup_label}"
                for rule_id in followup_rule_ids
            ]
        if not subqueries or self.planning_runtime is None:
            return None

        common_time = self.planning_runtime.validator.resolver.resolve(
            TimeExpression(raw_text=user_message),
            now=self.planning_runtime.now_provider(),
        )
        forced_time_range = (
            (
                common_time.start_time.isoformat(),
                common_time.end_time.isoformat(),
            )
            if common_time is not None
            else (
                (run_state.current_stat_start, run_state.current_stat_end)
                if followup_rule_ids
                and run_state.current_stat_start
                and run_state.current_stat_end
                else None
            )
        )
        if forced_time_range is not None:
            run_state.current_stat_start = forced_time_range[0]
            run_state.current_stat_end = forced_time_range[1]
        self._trace(
            node_name="compound_request_split",
            node_type="code",
            status="success",
            duration_ms=1,
            input_data={"user_message": user_message},
            output_data={
                "subqueries": subqueries,
                "followup_kind": followup_kind,
                "followup_rule_ids": followup_rule_ids,
                "common_time_range": forced_time_range,
            },
            processing_data={
                "description": "按明确并列连接词或已保存的复数指标指代拆分子任务，并绑定同一统计周期。"
            },
            config_data={"splitter": "deterministic_compound_indicator", "max_tasks": 3},
        )

        base_state = self._compound_subtask_state(run_state)
        subtask_specs = []
        for index, subquery in enumerate(subqueries, start=1):
            child_state = self._compound_subtask_state(base_state)
            child_state.subtask_id = f"{context.request_id}:subtask:{index}"
            if followup_rule_ids:
                plan_override = _compound_followup_plan(
                    followup_rule_ids[index - 1],
                    str(followup_kind),
                )
            else:
                plan_override = (
                    _compound_result_plan(subquery)
                    if forced_time_range is not None
                    and _classify_request_kind(user_message) == "trial_run"
                    else None
                )
            if plan_override is not None:
                self._trace(
                    node_name="compound_subtask_plan",
                    node_type="code",
                    status="success",
                    duration_ms=1,
                    input_data={"subquery": subquery, "subtask_index": index},
                    output_data={
                        "request_plan": plan_override.model_dump(mode="json")
                    },
                    processing_data={
                        "description": "把带统一统计周期的结果子句编译为独立指标业务计划，避免省略句依赖 Planner 补全。"
                    },
                    config_data={"planner": "deterministic_compound_result"},
                    subtask_id=child_state.subtask_id,
                )
            subtask_specs.append((index, subquery, child_state, plan_override))

        serial_required = any(term in user_message for term in (
            "上传", "文件对比", "规则变更", "修改口径", "发布", "审批",
        ))
        execution_semaphore = (
            asyncio.Semaphore(1)
            if serial_required
            else self.compound_semaphore
        )

        async def execute_subtask(spec):
            index, subquery, child_state, plan_override = spec
            queued_at = time.perf_counter()
            self._trace(
                node_name="compound_subtask_queue",
                node_type="code",
                status="success",
                duration_ms=1,
                input_data={"subquery": subquery, "subtask_index": index},
                output_data={"concurrency_limit": 1 if serial_required else self.compound_concurrency},
                processing_data={"description": "子任务进入轻量并发控制队列。"},
                config_data={"provider": self.model_provider},
                subtask_id=child_state.subtask_id,
            )
            async with execution_semaphore:
                queue_ms = max(0, int((time.perf_counter() - queued_at) * 1000))
                execute_started = time.perf_counter()
                try:
                    result = await self._run(
                        subquery,
                        context,
                        child_state,
                        allow_compound=False,
                        forced_time_range=forced_time_range,
                        request_plan_override=plan_override,
                    )
                except asyncio.CancelledError:
                    child_state.cancelled = True
                    raise
                except Exception:
                    logger.exception("compound subtask failed")
                    child_state.stop_reason = "tool_error"
                    result = AgentRunResult(
                        answer="该指标子任务执行失败，请单独重试。",
                        stop_reason="tool_error",
                        state=child_state,
                    )
                execute_ms = max(
                    0, int((time.perf_counter() - execute_started) * 1000)
                )
                self._trace(
                    node_name="compound_subtask_execute",
                    node_type="code",
                    status=(
                        "success"
                        if result.stop_reason == "final_answer"
                        else "warning"
                    ),
                    duration_ms=execute_ms,
                    exclusive_duration_ms=0,
                    input_data={"subquery": subquery, "subtask_index": index},
                    output_data={
                        "queue_duration_ms": queue_ms,
                        "execution_duration_ms": execute_ms,
                        "stop_reason": result.stop_reason,
                    },
                    processing_data={"description": "在隔离子状态中完成子任务执行。"},
                    config_data={"provider": self.model_provider},
                    subtask_id=child_state.subtask_id,
                )
                return index, result

        completed = await asyncio.gather(
            *(execute_subtask(spec) for spec in subtask_specs)
        )
        completed.sort(key=lambda item: item[0])
        sections: list[str] = []
        resolved_rule_ids: list[str] = []
        model_name: str | None = None
        successful_count = 0
        failure_reason = "tool_error"
        for index, child_result in completed:
            self._merge_compound_subtask_state(run_state, child_result.state)
            if child_result.state.current_rule_id:
                resolved_rule_ids.append(child_result.state.current_rule_id)
            title = _latest_rule_name(child_result.state) or f"子任务 {index}"
            sections.append(f"## {title}\n\n{child_result.answer}")
            if child_result.stop_reason == "final_answer":
                successful_count += 1
            else:
                failure_reason = child_result.stop_reason
            if child_result.model:
                model_name = child_result.model

        stop_reason = "final_answer" if successful_count else failure_reason

        active_rule_ids = followup_rule_ids or list(dict.fromkeys(resolved_rule_ids))
        run_state.current_rule_ids = active_rule_ids[:3] if len(active_rule_ids) >= 2 else []
        self._trace(
            node_name="compound_result_merge",
            node_type="code",
            status="success" if stop_reason == "final_answer" else "warning",
            duration_ms=1,
            input_data={"subtask_count": len(subqueries)},
            output_data={
                "section_count": len(sections),
                "active_rule_ids": run_state.current_rule_ids,
                "stop_reason": stop_reason,
                "successful_subtasks": successful_count,
                "failed_subtasks": len(completed) - successful_count,
            },
            processing_data={"description": "合并各指标的独立证据回答和明细入口。"},
            config_data={"merger": "deterministic_compound_answer"},
        )
        run_state.stop_reason = stop_reason
        return AgentRunResult(
            answer="\n\n---\n\n".join(sections),
            stop_reason=stop_reason,
            state=run_state,
            model=model_name,
        )

    async def run(
        self,
        user_message: str,
        context: AgentRuntimeContext,
        state: AgentRunState | None = None,
    ) -> AgentRunResult:
        run_state = state or AgentRunState()
        emit_agent_event(self.event_callback, "agent_start", step=0)
        if run_state.cancelled:
            run_state.stop_reason = "cancelled"
            result = AgentRunResult(
                answer="本次运行已取消。",
                stop_reason="cancelled",
                state=run_state,
            )
            self._emit_terminal(result)
            return result
        try:
            compound_count = 1
            if self.planning_runtime is not None:
                explicit_count = len(_split_compound_indicator_query(user_message))
                compound_count = (
                    explicit_count
                    or (
                        len(run_state.current_rule_ids)
                        if _compound_followup_kind(user_message, run_state)
                        else 1
                    )
                )
            # 并发子任务按实际批次数扩展总超时；本地 Ollama 并发为 1，
            # API 默认并发 2，避免简单地按子任务总数线性放大等待窗口。
            compound_batches = max(
                1,
                (compound_count + self.compound_concurrency - 1)
                // self.compound_concurrency,
            )
            result = await asyncio.wait_for(
                self._run(user_message, context, run_state),
                timeout=self.request_timeout_seconds * compound_batches,
            )
        except TimeoutError:
            run_state.stop_reason = "request_timeout"
            result = AgentRunResult(
                answer="本次请求处理超时，请稍后重试。",
                stop_reason="request_timeout",
                state=run_state,
            )
        self._emit_terminal(result)
        return result

    def _emit_terminal(self, result: AgentRunResult) -> None:
        if result.answer:
            emit_agent_event(
                self.event_callback,
                "assistant_message",
                step=result.state.step_count,
                message=result.answer,
            )
        event = (
            "agent_error"
            if result.stop_reason
            in {"tool_error", "request_timeout", "context_conflict"}
            else "agent_done"
        )
        emit_agent_event(
            self.event_callback,
            event,
            stop_reason=result.stop_reason,
            step_count=result.state.step_count,
            model_name=result.model,
            answer=result.answer,
            fallback_category=result.state.fallback_category,
            failure_code=result.state.failure_code,
        )

    async def _run(
        self,
        user_message: str,
        context: AgentRuntimeContext,
        run_state: AgentRunState,
        *,
        allow_compound: bool = True,
        forced_time_range: tuple[str, str] | None = None,
        request_plan_override: RequestPlan | None = None,
    ) -> AgentRunResult:
        turn_tool_results_start = len(run_state.last_tool_results)
        if not run_state.subtask_id:
            run_state.subtask_id = context.request_id
        _ACTIVE_SUBTASK_ID.set(run_state.subtask_id)
        if allow_compound and self.planning_runtime is not None:
            compound_result = await self._run_compound_request(
                user_message,
                context,
                run_state,
            )
            if compound_result is not None:
                return compound_result
        detail_answer = _compose_existing_detail_answer(user_message, run_state)
        if detail_answer is not None:
            self._trace(
                node_name="detail_followup_resolve",
                node_type="code",
                status="success",
                duration_ms=1,
                input_data={
                    "user_message": user_message,
                    "last_run_id": run_state.last_run_id,
                },
                output_data={"answer": detail_answer},
                processing_data={
                    "description": "识别分子或分母明细追问，并复用最近一次成功试运行入口。"
                },
                config_data={"resolver": "deterministic_detail_followup"},
            )
            run_state.stop_reason = "final_answer"
            return AgentRunResult(
                answer=detail_answer,
                stop_reason="final_answer",
                state=run_state,
            )
        planning_execution = None
        if self.planning_runtime is not None:
            try:
                planning_execution = await self.planning_runtime.prepare(
                    user_message,
                    context,
                    run_state,
                    forced_time_range=forced_time_range,
                    request_plan_override=request_plan_override,
                )
            except AgentPlanningError:
                logger.warning("agent planner rejected model output", exc_info=True)
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="无法生成有效业务计划，请重新描述目标。",
                    stop_reason="tool_error",
                    state=run_state,
                )
            except Exception:
                logger.exception("agent planner failed")
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="业务计划生成失败，请稍后重试。",
                    stop_reason="tool_error",
                    state=run_state,
                )
        if not run_state.messages:
            run_state.messages.append({"role": "system", "content": AGENT_SYSTEM_PROMPT})
        run_state.current_request_kind = _request_kind_from_plan(
            user_message,
            planning_execution,
        )
        run_state.messages.append({"role": "user", "content": user_message})
        model_name: str | None = None
        evidence_corrections = 0
        chinese_corrections = 0
        trial_run_corrections = 0
        fact_corrections = 0
        planned_tool_corrections = 0
        empty_answer_corrections = 0
        tool_protocol_corrections = 0
        for _ in range(self.max_steps):
            replanned = False
            plan_corrected = False
            try:
                if run_state.cancelled:
                    run_state.stop_reason = "cancelled"
                    return AgentRunResult(
                        answer="本次运行已取消。",
                        stop_reason="cancelled",
                        state=run_state,
                        model=model_name,
                    )
                run_state.step_count += 1
                decision = None
                if planning_execution is not None:
                    controller_started = time.perf_counter()
                    decision = self.planning_runtime.next_decision(
                        planning_execution, run_state
                    )
                    decision_is_clarification = (
                        decision.action.value == "fallback"
                        and decision.fallback_category is not None
                        and decision.fallback_category.value
                        in {"USER_CLARIFICATION", "BUSINESS_CONFIRMATION"}
                    )
                    self._trace(
                        node_name="state_controller",
                        node_type="code",
                        status=(
                            "warning"
                            if decision_is_clarification
                            else "failed"
                            if decision.action.value == "fallback"
                            else "success"
                        ),
                        duration_ms=max(1, int((time.perf_counter() - controller_started) * 1000)),
                        input_data={
                            "compiled_plan": planning_execution.compiled_plan.model_dump(mode="json"),
                            "validation": planning_execution.validation.model_dump(mode="json"),
                            "state": run_state.model_dump(mode="json"),
                        },
                        output_data={"decision": decision.model_dump(mode="json")},
                        processing_data={
                            "description": "根据已完成事实选择下一业务能力，并只开放当前允许的工具。"
                        },
                        config_data={
                            "controller": type(self.planning_runtime.controller).__name__,
                            "prompt_file": "agent_final_answer_step.txt",
                            "prompt_version": prompt_version("agent_final_answer_step"),
                        },
                    )
                    if decision.action.value == "fallback":
                        clarification = decision_is_clarification
                        stop_reason = "need_clarification" if clarification else "tool_error"
                        run_state.stop_reason = stop_reason
                        run_state.fallback_category = (
                            decision.fallback_category.value
                            if decision.fallback_category is not None
                            else None
                        )
                        run_state.failure_code = decision.code or None
                        if clarification:
                            emit_agent_event(
                                self.event_callback,
                                "clarification_required",
                                step=run_state.step_count,
                                message=decision.message,
                                fallback_category=run_state.fallback_category,
                                failure_code=run_state.failure_code,
                            )
                        return AgentRunResult(
                            answer=decision.message or "当前计划无法继续执行。",
                            stop_reason=stop_reason,
                            state=run_state,
                            model=model_name,
                        )
                    deterministic_answer = _compose_implementation_validation_answer(
                        run_state.last_tool_results[turn_tool_results_start:]
                    ) or _compose_prepared_sql_answer(
                        planning_execution, run_state
                    ) or _compose_rule_components_answer(
                        user_message,
                        run_state.last_tool_results[turn_tool_results_start:],
                    ) or _compose_upload_comparison_answer(
                        run_state.last_tool_results[turn_tool_results_start:]
                    )
                    if (
                        decision.action.value == "compose_answer"
                        and deterministic_answer is not None
                    ):
                        verify_started = time.perf_counter()
                        verification = self.planning_runtime.verify(
                            planning_execution, run_state, context
                        )
                        self._trace(
                            node_name="plan_verify",
                            node_type="code",
                            status="success" if verification.ok else "failed",
                            duration_ms=max(
                                1,
                                int((time.perf_counter() - verify_started) * 1000),
                            ),
                            input_data={
                                "compiled_plan": planning_execution.compiled_plan.model_dump(mode="json"),
                                "state": run_state.model_dump(mode="json"),
                                "context": context.model_dump(mode="json"),
                            },
                            output_data={
                                "verification": verification.model_dump(mode="json"),
                                "verified_evidence_ids": verification.verified_evidence_ids,
                            },
                            processing_data={
                                "description": "校验规则、医院、统计时间和 SQL 证据链一致性。"
                            },
                            config_data={
                                "verifier": type(self.planning_runtime.verifier).__name__
                            },
                        )
                        if not verification.ok:
                            run_state.stop_reason = "tool_error"
                            return AgentRunResult(
                                answer=verification.message,
                                stop_reason="tool_error",
                                state=run_state,
                                model=model_name,
                            )
                        self._trace(
                            node_name="response_guard",
                            node_type="code",
                            status="success",
                            duration_ms=1,
                            input_data={
                                "deterministic_evidence": next(
                                    (
                                        item
                                        for item in reversed(
                                            run_state.last_tool_results[
                                                turn_tool_results_start:
                                            ]
                                        )
                                        if isinstance(item, dict)
                                        and item.get("code")
                                        in {
                                            "UPLOAD_ANALYZED",
                                            "SQL_OBJECT_PREPARED",
                                            "EFFECTIVE_RULE_FOUND",
                                            "IMPLEMENTATION_VALIDATION_COMPLETED",
                                        }
                                    ),
                                    {},
                                )
                            },
                            output_data={"answer": deterministic_answer},
                            processing_data={
                                "description": "使用已校验的规则、SQL 或上传对比证据确定性生成回答，不再交由模型补充无证据结论。"
                            },
                            config_data={"guard": "deterministic_evidence_response"},
                        )
                        deterministic_answer = _append_trial_detail_export(
                            deterministic_answer,
                            run_state.last_tool_results[turn_tool_results_start:],
                        )
                        run_state.stop_reason = "final_answer"
                        return AgentRunResult(
                            answer=deterministic_answer,
                            stop_reason="final_answer",
                            state=run_state,
                            model=model_name,
                        )
                    direct_response = None
                    if decision.action.value == "execute_tool":
                        dispatch_started = time.perf_counter()
                        try:
                            direct_call = build_deterministic_tool_call(
                                planning_execution,
                                decision,
                                run_state,
                                user_message=user_message,
                            )
                        except DeterministicDispatchError as exc:
                            self._trace(
                                node_name="deterministic_tool_dispatch",
                                node_type="code",
                                status=(
                                    "warning" if exc.needs_clarification else "failed"
                                ),
                                duration_ms=max(
                                    1,
                                    int(
                                        (time.perf_counter() - dispatch_started)
                                        * 1000
                                    ),
                                ),
                                input_data={
                                    "request_plan": planning_execution.request_plan.model_dump(mode="json"),
                                    "decision": decision.model_dump(mode="json"),
                                    "state": run_state.model_dump(mode="json"),
                                    "user_message": user_message,
                                },
                                output_data={
                                    "code": exc.code,
                                    "message": str(exc),
                                },
                                processing_data={
                                    "description": "根据已校验计划和结构化状态编译唯一工具及参数。"
                                },
                                config_data={
                                    "dispatcher": "build_deterministic_tool_call"
                                },
                                error_code=exc.code,
                                error_message=str(exc),
                            )
                            stop_reason = (
                                "need_clarification"
                                if exc.needs_clarification
                                else "tool_error"
                            )
                            run_state.stop_reason = stop_reason
                            return AgentRunResult(
                                answer=str(exc),
                                stop_reason=stop_reason,
                                state=run_state,
                                model=model_name,
                            )
                        self._trace(
                            node_name="deterministic_tool_dispatch",
                            node_type="code",
                            status="success",
                            duration_ms=max(
                                1,
                                int(
                                    (time.perf_counter() - dispatch_started) * 1000
                                ),
                            ),
                            input_data={
                                "request_plan": planning_execution.request_plan.model_dump(mode="json"),
                                "decision": decision.model_dump(mode="json"),
                                "state": run_state.model_dump(mode="json"),
                                "user_message": user_message,
                            },
                            output_data={
                                "tool_call": direct_call.model_dump(mode="json")
                            },
                            processing_data={
                                "description": "根据已校验计划和结构化状态编译唯一工具及参数。"
                            },
                            config_data={
                                "dispatcher": "build_deterministic_tool_call"
                            },
                        )
                        direct_response = AgentModelResponse(
                            tool_calls=[direct_call]
                        )
                    else:
                        if decision.action.value == "compose_answer":
                            verify_started = time.perf_counter()
                            verification = self.planning_runtime.verify(
                                planning_execution, run_state, context
                            )
                            self._trace(
                                node_name="plan_verify",
                                node_type="code",
                                status="success" if verification.ok else "failed",
                                duration_ms=max(1, int((time.perf_counter() - verify_started) * 1000)),
                                input_data={
                                    "compiled_plan": planning_execution.compiled_plan.model_dump(mode="json"),
                                    "evidence_ids": run_state.evidence_ids,
                                    "context": context.model_dump(mode="json"),
                                    "subtask_id": run_state.subtask_id,
                                },
                                output_data={
                                    "verification": verification.model_dump(mode="json"),
                                    "verified_evidence_ids": verification.verified_evidence_ids,
                                },
                                processing_data={
                                    "description": "在生成最终回答前验证证据链，并只开放已验证 Evidence。"
                                },
                                config_data={
                                    "verifier": type(self.planning_runtime.verifier).__name__,
                                    "verifier_version": getattr(self.planning_runtime.verifier, "version", "unknown"),
                                },
                            )
                            if not verification.ok:
                                run_state.stop_reason = "tool_error"
                                return AgentRunResult(
                                    answer=verification.message,
                                    stop_reason="tool_error",
                                    state=run_state,
                                    model=model_name,
                                )
                            if run_state.evidence_ids and not set(run_state.evidence_ids).issubset(
                                set(run_state.verified_evidence_ids)
                            ):
                                run_state.stop_reason = "tool_error"
                                return AgentRunResult(
                                    answer="当前证据尚未完成验证，不能生成最终结论。",
                                    stop_reason="tool_error",
                                    state=run_state,
                                    model=model_name,
                                )
                        available = self.registry.list_for_names(
                            decision.tool_names, context, run_state
                        )
                        run_state.messages.append({
                            "role": "system",
                            "content": self.planning_runtime.instruction(
                                planning_execution, decision, run_state
                            ),
                        })
                else:
                    direct_response = None
                    available = self.registry.list_for_context(context, run_state)
                if direct_response is not None:
                    response = direct_response
                    empty_model_action = False
                else:
                    model_messages = list(run_state.messages)
                    if (
                        planning_execution is not None
                        and decision is not None
                        and decision.action.value == "compose_answer"
                        and run_state.evidence_ids
                    ):
                        model_messages = _verified_final_messages(
                            run_state.messages,
                            run_state.verified_evidence_ids,
                        )
                    emit_agent_event(
                        self.event_callback,
                        "model_start",
                        step=run_state.step_count,
                        model_name=model_name,
                        tool_count=len(available),
                    )
                    tool_schemas = self.registry.to_ollama_schema(available)
                    model_input = {
                        "messages": model_messages,
                        "tools": tool_schemas,
                        "temperature": 0.0,
                    }
                    model_started = time.perf_counter()
                    try:
                        response = await self.adapter.chat(
                            messages=model_messages,
                            tools=tool_schemas,
                            temperature=0.0,
                        )
                    except AgentModelError as exc:
                        self._trace(
                            node_name="final_answer_llm",
                            node_type="llm",
                            status="failed",
                            duration_ms=max(1, int((time.perf_counter() - model_started) * 1000)),
                            input_data=model_input,
                            output_data={"error": str(exc)},
                            processing_data={"description": "根据本轮已验证证据生成最终回答。"},
                            config_data={"adapter": type(self.adapter).__name__, "step": run_state.step_count},
                            error_message=str(exc),
                        )
                        run_state.stop_reason = "tool_error"
                        return AgentRunResult(
                            answer=str(exc) or "模型服务暂时不可用，请稍后重试。",
                            stop_reason="tool_error",
                            state=run_state,
                        )
                    except Exception:
                        self._trace(
                            node_name="final_answer_llm",
                            node_type="llm",
                            status="failed",
                            duration_ms=max(1, int((time.perf_counter() - model_started) * 1000)),
                            input_data=model_input,
                            output_data={"error": "模型调用异常"},
                            processing_data={"description": "根据本轮已验证证据生成最终回答。"},
                            config_data={"adapter": type(self.adapter).__name__, "step": run_state.step_count},
                            error_message="模型调用异常",
                        )
                        run_state.stop_reason = "tool_error"
                        return AgentRunResult(
                            answer="模型调用异常，请稍后重试。",
                            stop_reason="tool_error",
                            state=run_state,
                        )
                    model_name = response.model or model_name
                    empty_model_action = (
                        not response.content.strip() and not response.tool_calls
                    )
                    self._trace(
                        node_name="final_answer_llm",
                        node_type="llm",
                        status="warning" if empty_model_action else "success",
                        duration_ms=max(1, int((time.perf_counter() - model_started) * 1000)),
                        input_data=model_input,
                        output_data={
                            "model": response.model,
                            "content": response.content,
                            "tool_calls": [call.model_dump(mode="json") for call in response.tool_calls],
                            "usage": response.usage,
                        },
                        processing_data={"description": "根据本轮已验证证据生成最终回答。"},
                        config_data={
                            "adapter": type(self.adapter).__name__,
                            "step": run_state.step_count,
                            "prompt_file": "agent_final_answer.txt",
                            "prompt_version": prompt_version("agent_final_answer"),
                        },
                        error_code=(
                            "MODEL_EMPTY_ACTION" if empty_model_action else ""
                        ),
                        error_message=(
                            "模型未生成回答或工具调用。"
                            if empty_model_action
                            else ""
                        ),
                    )
                assistant_message = {
                    "role": "assistant",
                    "content": response.content,
                    "tool_calls": [call.model_dump(mode="json") for call in response.tool_calls],
                }
                run_state.messages.append(assistant_message)
                if empty_model_action:
                    empty_answer_corrections += 1
                    if (
                        empty_answer_corrections <= 1
                        and run_state.step_count < self.max_steps
                    ):
                        run_state.messages.append({
                            "role": "system",
                            "content": EMPTY_ANSWER_PROMPT,
                        })
                        continue
                    run_state.stop_reason = "tool_error"
                    return AgentRunResult(
                        answer="模型未生成有效回答，请稍后重试。",
                        stop_reason="tool_error",
                        state=run_state,
                        model=model_name,
                    )
                if not response.tool_calls:
                    guard_started = time.perf_counter()
                    if contains_tool_protocol_markup(response.content):
                        tool_protocol_corrections += 1
                        self._trace(
                            node_name="response_guard",
                            node_type="code",
                            status=(
                                "warning"
                                if tool_protocol_corrections <= 1
                                else "failed"
                            ),
                            duration_ms=max(
                                1,
                                int((time.perf_counter() - guard_started) * 1000),
                            ),
                            input_data={"raw_content": response.content},
                            output_data={"answer": ""},
                            processing_data={
                                "description": "阻止模型把内部工具协议标记输出给用户。"
                            },
                            config_data={"guard": "tool_protocol_guard"},
                            error_code="TOOL_PROTOCOL_LEAK",
                            error_message="模型在最终回答中输出了工具协议标记。",
                        )
                        if (
                            tool_protocol_corrections <= 1
                            and run_state.step_count < self.max_steps
                        ):
                            run_state.messages.append({
                                "role": "system",
                                "content": final_answer_correction(
                                    "tool_protocol_forbidden"
                                ),
                            })
                            continue
                        run_state.stop_reason = "tool_error"
                        return AgentRunResult(
                            answer="模型未生成有效业务回答，请重新发送问题。",
                            stop_reason="tool_error",
                            state=run_state,
                            model=model_name,
                        )
                    answer = normalize_agent_answer(response.content)
                    answer = _append_trial_detail_export(
                        answer,
                        run_state.last_tool_results[turn_tool_results_start:],
                    )
                    assistant_message["content"] = answer
                    if (
                        planning_execution is not None
                        and decision is not None
                        and decision.action.value == "execute_tool"
                    ):
                        if (
                            planned_tool_corrections < 1
                            and run_state.step_count < self.max_steps
                        ):
                            planned_tool_corrections += 1
                            run_state.messages.append({
                                "role": "system",
                                "content": (
                                    final_answer_correction(
                                        "premature_answer",
                                        tool_names="、".join(decision.tool_names),
                                    )
                                ),
                            })
                            continue
                        run_state.stop_reason = "tool_error"
                        return AgentRunResult(
                            answer="当前计划需要先完成受控工具步骤，模型未按计划执行。",
                            stop_reason="tool_error",
                            state=run_state,
                            model=model_name,
                        )
                    requires_evidence = (
                        bool(planning_execution.compiled_plan.required_facts)
                        if planning_execution is not None
                        else True
                    )
                    if requires_evidence and not run_state.evidence:
                        evidence_corrections += 1
                        if evidence_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": EVIDENCE_REQUIRED_PROMPT,
                            })
                            continue
                    if not _contains_chinese(answer):
                        chinese_corrections += 1
                        if chinese_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": CHINESE_REQUIRED_PROMPT,
                            })
                            continue
                    if (
                        run_state.current_request_kind == "trial_run"
                        and not _has_fact_type(run_state, "trial_run")
                        and not _asks_for_period_clarification(answer)
                    ):
                        trial_run_corrections += 1
                        if trial_run_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": TRIAL_RUN_REQUIRED_PROMPT,
                            })
                            continue
                    missing = missing_fact_types(answer, run_state.evidence)
                    if missing:
                        fact_corrections += 1
                        if fact_corrections <= 1:
                            run_state.messages.append({
                                "role": "system",
                                "content": evidence_correction_prompt(missing),
                            })
                            continue
                    self._trace(
                        node_name="response_guard",
                        node_type="code",
                        status="success",
                        duration_ms=max(1, int((time.perf_counter() - guard_started) * 1000)),
                        input_data={
                            "raw_content": response.content,
                            "evidence": run_state.evidence,
                        },
                        output_data={"answer": answer, "missing_fact_types": []},
                        processing_data={
                            "description": "规范 Markdown 和公式格式，并阻止缺少工具证据的完成性声明。"
                        },
                        config_data={"guard": "deterministic_response_guard"},
                    )
                    run_state.stop_reason = "final_answer"
                    return AgentRunResult(
                        answer=answer,
                        stop_reason="final_answer",
                        state=run_state,
                        model=model_name,
                    )
            except Exception:
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="处理请求时发生内部错误，请稍后重试。",
                    stop_reason="tool_error",
                    state=run_state,
                    model=model_name,
                )
            call_limit = 1 if planning_execution is not None else self.max_tool_calls_per_step
            if len(response.tool_calls) > call_limit:
                run_state.stop_reason = "tool_error"
                return AgentRunResult(
                    answer="单步工具调用数量超过限制，本次运行已停止。",
                    stop_reason="tool_error",
                    state=run_state,
                    model=model_name,
                )
            for call in response.tool_calls:
                try:
                    if (
                        planning_execution is not None
                        and decision is not None
                        and call.name not in decision.tool_names
                    ):
                        if (
                            planned_tool_corrections < 1
                            and run_state.step_count < self.max_steps
                        ):
                            planned_tool_corrections += 1
                            correction = final_answer_correction(
                                "tool_outside_plan",
                                tool_names="、".join(decision.tool_names),
                            )
                            run_state.messages.append({
                                "role": "tool",
                                "tool_name": call.name,
                                "content": json.dumps({
                                    "ok": False,
                                    "status": "unavailable",
                                    "code": "TOOL_OUTSIDE_PLAN",
                                    "summary": correction,
                                }, ensure_ascii=False),
                            })
                            run_state.messages.append({
                                "role": "system",
                                "content": correction,
                            })
                            plan_corrected = True
                            break
                        run_state.stop_reason = "tool_error"
                        return AgentRunResult(
                            answer="模型调用了当前计划未允许的工具，本次运行已停止。",
                            stop_reason="tool_error",
                            state=run_state,
                            model=model_name,
                        )
                    if run_state.cancelled:
                        run_state.stop_reason = "cancelled"
                        return AgentRunResult(
                            answer="本次运行已取消。",
                            stop_reason="cancelled",
                            state=run_state,
                            model=model_name,
                        )
                    result = await self.gateway.execute(
                        call.name, call.arguments, context, run_state
                    )
                    dumped = result.model_dump(mode="json")
                    run_state.last_tool_results.append(dumped)
                    if result.ok:
                        run_state.evidence.extend(
                            evidence.model_dump(mode="json") for evidence in result.evidence
                        )
                    run_state.messages.append({
                        "role": "tool",
                        "tool_name": call.name,
                        "content": json.dumps(dumped, ensure_ascii=False),
                    })
                    if planning_execution is not None and not result.ok:
                        replacement = await self.planning_runtime.try_replan(
                            planning_execution,
                            query=user_message,
                            context=context,
                            state=run_state,
                            failure_code=result.code,
                            failure_reason=result.summary,
                        )
                        if replacement is not None:
                            planning_execution = replacement
                            replanned = True
                            break
                    if result.status == "need_clarification":
                        emit_agent_event(
                            self.event_callback,
                            "clarification_required",
                            step=run_state.step_count,
                            message=result.summary,
                        )
                        run_state.stop_reason = "need_clarification"
                        return AgentRunResult(
                            answer=result.summary,
                            stop_reason="need_clarification",
                            state=run_state,
                            model=model_name,
                        )
                    if run_state.stop_reason == "context_conflict":
                        return AgentRunResult(
                            answer=result.summary,
                            stop_reason="context_conflict",
                            state=run_state,
                            model=model_name,
                        )
                    if (
                        result.code == "TOOL_NOT_FOUND"
                        or result.status
                        in {"forbidden", "unavailable", "cancelled", "error"}
                    ) and not result.retryable:
                        stop_reason = (
                            "cancelled" if result.status == "cancelled" else "tool_error"
                        )
                        run_state.stop_reason = stop_reason
                        return AgentRunResult(
                            answer=result.summary,
                            stop_reason=stop_reason,
                            state=run_state,
                            model=model_name,
                        )
                    if run_state.stop_reason == "repeated_tool_call":
                        return AgentRunResult(
                            answer="检测到重复工具调用，本次运行已停止。",
                            stop_reason="repeated_tool_call",
                            state=run_state,
                            model=model_name,
                        )
                except Exception:
                    run_state.stop_reason = "tool_error"
                    return AgentRunResult(
                        answer="工具执行异常，请稍后重试。",
                        stop_reason="tool_error",
                        state=run_state,
                        model=model_name,
                    )
            if replanned:
                continue
            if plan_corrected:
                continue
        run_state.stop_reason = "max_steps"
        return AgentRunResult(
            answer="已达到最大处理步骤，请缩小问题范围后重试。",
            stop_reason="max_steps",
            state=run_state,
            model=model_name,
        )

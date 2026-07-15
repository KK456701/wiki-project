"""将确定性诊断证据组织成医生和实施人员可读的说明。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


_PROMPT_PATH = Path(__file__).resolve().parents[1] / "prompts" / "diagnosis_compose.txt"


def _status_text(status: str) -> str:
    return {
        "success": "执行成功",
        "empty": "执行成功但没有返回数据",
        "failed": "执行失败",
        "blocked": "安全检查未通过，未执行",
    }.get(status, status or "未执行")


def _number(value: Any) -> str:
    return "--" if value is None else str(value)


class DiagnosisNarrator:
    def __init__(self, llm_client: Any | None) -> None:
        self.llm_client = llm_client

    @staticmethod
    def _safe_facts(diagnosis: dict[str, Any]) -> dict[str, Any]:
        evidence = diagnosis.get("evidence") or {}
        executions = diagnosis.get("execution_results") or {}
        return {
            "primary_conclusion": diagnosis.get("primary_conclusion"),
            "findings": diagnosis.get("findings") or [],
            "comparison_rows": diagnosis.get("comparison_rows") or [],
            "effective_source": diagnosis.get("effective_source") or {},
            "execution_results": {
                "user": executions.get("user") or {},
                "current": executions.get("hospital") or {},
            },
            "context": {
                "rule_id": evidence.get("rule_id"),
                "question": evidence.get("question"),
                "stat_period": evidence.get("stat_period"),
                "declared_param_names": sorted((evidence.get("declared_params") or {}).keys()),
            },
        }

    @staticmethod
    def _fallback(diagnosis: dict[str, Any]) -> str:
        conclusion = str(diagnosis.get("primary_conclusion") or "")
        findings = list(diagnosis.get("findings") or [])
        comparison_rows = list(diagnosis.get("comparison_rows") or [])
        effective_source = dict(diagnosis.get("effective_source") or {})
        executions = dict(diagnosis.get("execution_results") or {})
        user = dict(executions.get("user") or {})
        current = dict(executions.get("hospital") or {})

        lines = ["## 结论"]
        if conclusion == "user_sql_blocked":
            reasons = "；".join(user.get("blocked_reasons") or ["未通过只读安全检查"])
            lines.append(f"你粘贴的 SQL 没有执行：{reasons}系统仍可继续核对已配置口径。")
        elif conclusion == "caliber_difference":
            if user.get("status") == "success" and current.get("status") == "success":
                lines.append(
                    f"用户 SQL 为 {_number(user.get('result_value'))}%，"
                    f"当前生效 SQL 为 {_number(current.get('result_value'))}%。"
                    "两段 SQL 都已完成只读试运行，结果不一致主要来自下表中的计算规则差异，"
                    "并非数据库连接问题。"
                )
            else:
                lines.append(
                    "已经识别到用户 SQL 与当前生效 SQL 的计算规则差异；"
                    "即使其中一段未能试运行，仍可先按静态分析结果核对口径。"
                )
        elif conclusion == "user_sql_execution_failed":
            lines.append("你粘贴的 SQL 已通过只读检查，但试运行没有成功；已保留静态口径分析结果。")
        else:
            lines.append("用户 SQL 与当前生效 SQL 之间没有发现会明显改变结果的口径差异。")

        lines.extend([
            "",
            "## SQL 试运行结果",
            "| 计算方式 | 执行状态 | 分子 | 分母 | 指标结果 |",
            "|---|---|---:|---:|---:|",
        ])
        labels = {"user": "用户 SQL", "hospital": "当前生效 SQL"}
        for key in ("user", "hospital"):
            item = dict(executions.get(key) or {})
            lines.append(
                f"| {labels[key]} | {_status_text(str(item.get('status') or ''))} | "
                f"{_number(item.get('numerator_count'))} | {_number(item.get('denominator_count'))} | "
                f"{_number(item.get('result_value'))} |"
            )
        for key in ("user", "hospital"):
            item = dict(executions.get(key) or {})
            if item.get("numerator_count") is not None and item.get("denominator_count") is not None:
                lines.append(
                    f"- {labels[key]}：{item['numerator_count']} / {item['denominator_count']}"
                    f" = {_number(item.get('result_value'))}%"
                )

        source_label = str(effective_source.get("label") or "当前已审批生效的计算口径")
        lines.extend([
            "",
            f"当前生效 SQL 来源：{source_label}。",
            "",
            "## 计算规则差异",
        ])
        if comparison_rows:
            lines.extend([
                "| 比较项目 | 用户 SQL | 当前生效 SQL | 对结果的影响 | 建议 |",
                "|---|---|---|---|---|",
            ])
            for row in comparison_rows:
                cells = [
                    str(row.get("item") or "--"),
                    str(row.get("user_sql") or "--"),
                    str(row.get("current_sql") or "--"),
                    str(row.get("impact") or "--"),
                    str(row.get("suggestion") or "--"),
                ]
                lines.append("| " + " | ".join(cell.replace("|", "\\|") for cell in cells) + " |")
        elif conclusion == "user_sql_blocked":
            lines.append("当前只确认了 SQL 安全问题，尚不能比较这段 SQL 的实际计算结果。")
        else:
            lines.append("现有筛选条件、时间边界和聚合方式中未识别到明确差异。")

        lines.extend(["", "## 建议怎么处理"])
        suggestions = list(dict.fromkeys(
            str(item.get("suggestion") or "").strip()
            for item in findings
            if str(item.get("suggestion") or "").strip()
        ))
        if suggestions:
            lines.extend(f"- {item}" for item in suggestions)
        elif conclusion == "user_sql_blocked":
            lines.append("- 请改为单条只读 SELECT 查询，移除写操作、动态 SQL、临时表或跨库引用后再试。")
        else:
            lines.append("- 如业务仍认为结果异常，请补充本次执行参数和聚合结果后再次诊断。")
        lines.extend([
            "",
            ":::details 查看口径来源与技术依据（供信息科和实施人员）",
            "",
        ])
        national = dict(executions.get("national") or {})
        if national:
            lines.append(
                f"- 国标参考 SQL：{_status_text(str(national.get('status') or ''))}。"
                "该状态仅用于追溯当前口径来源，不替代用户 SQL 与当前生效 SQL 的比较。"
            )
        overridden = effective_source.get("overridden_fields") or []
        if overridden:
            lines.append(f"- 本院生效口径调整项：{'、'.join(str(item) for item in overridden)}。")
        for finding in findings:
            lines.append(f"- `{finding.get('code', '')}`：{finding.get('evidence', '')}")
        lines.extend(["", ":::"])
        return "\n".join(lines)

    @staticmethod
    def _passes_guard(answer: str, diagnosis: dict[str, Any]) -> bool:
        required = (
            "## 结论",
            "## SQL 试运行结果",
            "## 计算规则差异",
            "## 建议怎么处理",
            "| 用户 SQL |",
            "| 当前生效 SQL |",
        )
        if not answer.strip() or any(item not in answer for item in required):
            return False
        if "| 国标口径 |" in answer:
            return False
        conclusion = diagnosis.get("primary_conclusion")
        if conclusion == "caliber_difference":
            if "口径" not in answer:
                return False
            if "数据库连接故障" in answer or "数据库故障" in answer:
                return False
            if any(
                str(row.get("item") or "") not in answer
                for row in diagnosis.get("comparison_rows") or []
            ):
                return False
        if conclusion == "user_sql_blocked" and "未执行" not in answer and "没有执行" not in answer:
            return False
        return True

    def compose(self, diagnosis: dict[str, Any]) -> str:
        fallback = self._fallback(diagnosis)
        if self.llm_client is None:
            return fallback
        try:
            template = _PROMPT_PATH.read_text(encoding="utf-8")
            facts = json.dumps(self._safe_facts(diagnosis), ensure_ascii=False, indent=2)
            answer = str(self.llm_client.generate(template.replace("{{facts}}", facts))).strip()
        except Exception:
            return fallback
        return answer if self._passes_guard(answer, diagnosis) else fallback

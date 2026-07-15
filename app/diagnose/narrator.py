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
        return {
            "primary_conclusion": diagnosis.get("primary_conclusion"),
            "findings": diagnosis.get("findings") or [],
            "execution_results": diagnosis.get("execution_results") or {},
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
        executions = dict(diagnosis.get("execution_results") or {})
        user = dict(executions.get("user") or {})

        lines = ["## 结论"]
        if conclusion == "user_sql_blocked":
            reasons = "；".join(user.get("blocked_reasons") or ["未通过只读安全检查"])
            lines.append(f"你粘贴的 SQL 没有执行：{reasons}系统仍可继续核对已配置口径。")
        elif conclusion == "caliber_difference":
            lines.append("两段 SQL 的结果不同，主要原因是统计口径存在差异，并非数据库连接问题。")
        elif conclusion == "user_sql_execution_failed":
            lines.append("你粘贴的 SQL 已通过只读检查，但试运行没有成功；已保留静态口径分析结果。")
        else:
            lines.append("当前没有发现会明显改变结果的口径差异。")

        lines.extend(["", "## 为什么不一致"])
        if findings:
            for index, finding in enumerate(findings, start=1):
                lines.append(
                    f"{index}. **{finding.get('title', '需关注')}**：{finding.get('evidence', '')}"
                    f"{finding.get('impact', '')}"
                )
        elif conclusion == "user_sql_blocked":
            lines.append("当前只确认了 SQL 安全问题，尚不能比较这段 SQL 的实际计算结果。")
        else:
            lines.append("现有结构、条件和聚合结果中未识别到明确差异。")

        lines.extend([
            "",
            "## 对结果的影响",
            "| 计算方式 | 执行状态 | 分子 | 分母 | 指标结果 |",
            "|---|---|---:|---:|---:|",
        ])
        labels = {"user": "用户 SQL", "national": "国标口径", "hospital": "本院生效口径"}
        for key in ("user", "national", "hospital"):
            item = dict(executions.get(key) or {})
            lines.append(
                f"| {labels[key]} | {_status_text(str(item.get('status') or ''))} | "
                f"{_number(item.get('numerator_count'))} | {_number(item.get('denominator_count'))} | "
                f"{_number(item.get('result_value'))} |"
            )
        for key in ("user", "national", "hospital"):
            item = dict(executions.get(key) or {})
            if item.get("numerator_count") is not None and item.get("denominator_count") is not None:
                lines.append(
                    f"- {labels[key]}：{item['numerator_count']} / {item['denominator_count']}"
                    f" = {_number(item.get('result_value'))}%"
                )

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
            "<details><summary>查看技术依据（供信息科和实施人员）</summary>",
            "",
        ])
        for finding in findings:
            lines.append(f"- `{finding.get('code', '')}`：{finding.get('evidence', '')}")
        lines.extend(["", "</details>"])
        return "\n".join(lines)

    @staticmethod
    def _passes_guard(answer: str, diagnosis: dict[str, Any]) -> bool:
        if not answer.strip() or "结论" not in answer or "建议" not in answer:
            return False
        conclusion = diagnosis.get("primary_conclusion")
        if conclusion == "caliber_difference":
            if "口径" not in answer:
                return False
            if "数据库连接故障" in answer or "数据库故障" in answer:
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

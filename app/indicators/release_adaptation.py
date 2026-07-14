from __future__ import annotations

import json
from typing import Any


class ReleaseAdaptationError(ValueError):
    pass


class ReleaseAdaptationService:
    def __init__(
        self,
        *,
        release_repository: Any,
        draft_repository: Any,
        generation_agent: Any,
    ) -> None:
        self.release_repository = release_repository
        self.draft_repository = draft_repository
        self.generation_agent = generation_agent

    def create(
        self,
        import_id: str,
        rule_id: str,
        hospital_id: str,
        actor_id: str,
    ) -> dict[str, Any]:
        detail = self.release_repository.read_import(import_id)
        if (
            detail.get("status") != "ready_for_adaptation"
            or detail.get("signature_status") != "verified"
            or detail.get("compatibility_status") != "compatible"
        ):
            raise ReleaseAdaptationError(
                "发布包尚未通过签名和兼容性检查，不能进入本院适配。"
            )

        item = next(
            (
                value
                for value in detail.get("items") or []
                if value.get("item_type") == "rule"
                and str(value.get("rule_id") or "") == rule_id
            ),
            None,
        )
        if item is None:
            raise ReleaseAdaptationError("发布包中没有找到指定指标规则。")

        item_path = str(item.get("item_path") or f"rules/{rule_id}.yaml")
        source_id = f"company_release:{import_id}:{item_path}"
        for draft in self.draft_repository.list(hospital_id):
            generated_by = (
                draft.get("generated_by")
                if isinstance(draft, dict)
                else getattr(draft, "generated_by", "")
            )
            if str(generated_by or "") == source_id:
                payload = (
                    dict(draft)
                    if isinstance(draft, dict)
                    else draft.model_dump(exclude_none=True)
                )
                return {**payload, "duplicate": True}

        payload = item.get("payload") if isinstance(item.get("payload"), dict) else {}
        preferred_name = str(payload.get("rule_name") or rule_id)
        query = _adaptation_query(rule_id, preferred_name, payload)
        created = self.generation_agent.create_adaptation_draft(
            query=query,
            hospital_id=hospital_id,
            actor_id=actor_id,
            base_index_code=rule_id,
            source_id=source_id,
            preferred_name=preferred_name,
        )
        result = dict(created) if isinstance(created, dict) else created.model_dump(exclude_none=True)
        return {**result, "duplicate": False}


def _adaptation_query(rule_id: str, rule_name: str, payload: dict[str, Any]) -> str:
    definition = str(payload.get("definition") or "")
    formula = str(payload.get("formula") or "")
    recommended = payload.get("recommended_params") or {}
    return (
        f"基于公司发布的已有指标 {rule_id}《{rule_name}》创建本院适配任务。"
        f"指标定义：{definition or '请根据标准指标补充'}。"
        f"标准公式：{formula or '请根据标准指标补充'}。"
        "必须保留该指标编码作为 base_index_code；先生成结构化计算计划，"
        "后续由本院人员确认实际数据库表字段，不要直接输出 SQL。"
        f"公司建议参数：{json.dumps(recommended, ensure_ascii=False, sort_keys=True)}。"
    )

"""把自然语言中的临时口径转换为可审计的会话状态。"""

from __future__ import annotations

from typing import Any

from app.memory.contracts import (
    ContextDelta,
    ContextOverride,
    ContextResolution,
    ConversationContext,
    ExecutionBlocker,
    ExecutionContextSnapshot,
    PendingClarification,
)


_CLEAR_MARKERS = ("恢复本院口径", "清除刚才的调整", "清除临时调整")
_WARD_ENTRY_VALUE = "ward_entry_time"


class ConversationContextService:
    """Deterministic-first resolver for session-scoped calculation changes."""

    def resolve(
        self,
        message: str,
        context: ConversationContext,
        *,
        effective_rule: Any | None = None,
        field_mapping: Any | None = None,
        source_message_id: int | None = None,
        llm_updates: list[dict[str, Any]] | None = None,
    ) -> ContextResolution:
        resolved = context.model_copy(deep=True)
        rule = self._as_dict(effective_rule)
        mapping = self._as_dict(field_mapping)
        delta = ContextDelta()

        next_rule_id = str(rule.get("rule_id") or "")
        previous_rule_id = resolved.active_rule.rule_id
        if previous_rule_id and next_rule_id and previous_rule_id != next_rule_id:
            resolved.working_caliber.overrides = []
            resolved.pending_clarifications = []
        if next_rule_id:
            resolved.active_rule.rule_id = next_rule_id
            resolved.active_rule.rule_name = str(rule.get("rule_name") or "")

        compact = "".join(str(message or "").split())
        if any(marker in compact for marker in _CLEAR_MARKERS):
            resolved.working_caliber.overrides = []
            resolved.pending_clarifications = []
            delta.clear_working_caliber = True
        else:
            self._extract_ward_entry_changes(
                compact,
                delta,
                source_message_id=source_message_id,
                source_text=message,
            )
            for update in llm_updates or []:
                candidate = self._validated_llm_override(update, source_message_id, message)
                if candidate is not None:
                    delta.overrides.append(candidate)
            if delta.clarification is not None:
                resolved.pending_clarifications = [delta.clarification]
            if delta.overrides:
                resolved.pending_clarifications = []
                for override in delta.overrides:
                    self._upsert_override(resolved, override)

        snapshot = self._build_snapshot(resolved, mapping)
        return ContextResolution(
            context=resolved,
            delta=delta,
            snapshot=snapshot,
            clarification=delta.clarification,
        )

    @staticmethod
    def _extract_ward_entry_changes(
        compact: str,
        delta: ContextDelta,
        *,
        source_message_id: int | None,
        source_text: str,
    ) -> None:
        if "入区" not in compact:
            return
        adjusts_both = "两者" in compact or "都按入区" in compact
        adjusts_period = any(
            marker in compact for marker in ("统计范围", "统计周期", "纳入统计", "筛选时间")
        )
        adjusts_elapsed = any(
            marker in compact for marker in ("48小时", "起点", "开始计时", "耗时")
        )
        if adjusts_both:
            adjusts_period = True
            adjusts_elapsed = True
        if not adjusts_period and not adjusts_elapsed:
            delta.clarification = PendingClarification(
                code="WARD_ENTRY_SCOPE_REQUIRED",
                question="你希望统计范围、48小时计算起点，还是两者都按入区时间？",
                options=[
                    "统计范围按入区时间",
                    "48小时从入区时间开始计算",
                    "两者都按入区时间",
                ],
                source_message_id=source_message_id,
            )
            return
        if adjusts_period:
            delta.overrides.append(
                ContextOverride(
                    key="period_time_field",
                    business_value=_WARD_ENTRY_VALUE,
                    status="pending_mapping",
                    source_message_id=source_message_id,
                    source_text=source_text,
                )
            )
        if adjusts_elapsed:
            delta.overrides.append(
                ContextOverride(
                    key="elapsed_time_start",
                    business_value=_WARD_ENTRY_VALUE,
                    status="pending_mapping",
                    source_message_id=source_message_id,
                    source_text=source_text,
                )
            )

    @staticmethod
    def _validated_llm_override(
        update: dict[str, Any], source_message_id: int | None, source_text: str
    ) -> ContextOverride | None:
        allowed = {
            "period_time_field",
            "elapsed_time_start",
            "threshold_minutes",
            "excluded_departments",
            "counting_method",
            "additional_filters",
        }
        key = str(update.get("key") or "")
        if key not in allowed or "value" not in update:
            return None
        return ContextOverride(
            key=key,
            business_value=update["value"],
            status="pending_mapping" if update["value"] == _WARD_ENTRY_VALUE else "ready",
            source_message_id=source_message_id,
            source_text=source_text,
        )

    @staticmethod
    def _upsert_override(context: ConversationContext, override: ContextOverride) -> None:
        context.working_caliber.overrides = [
            item for item in context.working_caliber.overrides if item.key != override.key
        ]
        context.working_caliber.overrides.append(override)

    def _build_snapshot(
        self, context: ConversationContext, field_mapping: dict[str, Any]
    ) -> ExecutionContextSnapshot:
        blockers: list[ExecutionBlocker] = []
        overrides: dict[str, Any] = {}
        resolved_fields: dict[str, str] = {}
        source_levels: dict[str, str] = {}
        fields = field_mapping.get("fields") or {}
        if not isinstance(fields, dict):
            fields = {}

        for clarification in context.pending_clarifications:
            blockers.append(
                ExecutionBlocker(
                    code=clarification.code,
                    message=clarification.question,
                )
            )
        for override in context.working_caliber.overrides:
            if override.business_value == _WARD_ENTRY_VALUE:
                mapped = str(fields.get(_WARD_ENTRY_VALUE) or "").strip()
                if mapped:
                    override.hospital_field = mapped
                    override.status = "ready"
                else:
                    override.hospital_field = None
                    override.status = "pending_mapping"
                    blockers.append(
                        ExecutionBlocker(
                            code="CONTEXT_FIELD_MAPPING_REQUIRED",
                            key=override.key,
                            message="已记住按入区时间计算，但尚未确认入区时间对应的医院字段。",
                        )
                    )
            overrides[override.key] = override.business_value
            source_levels[override.key] = "当前会话临时调整"
            if override.hospital_field:
                resolved_fields[override.key] = override.hospital_field

        return ExecutionContextSnapshot(
            context_version=context.context_version,
            rule_id=context.active_rule.rule_id,
            overrides=overrides,
            resolved_fields=resolved_fields,
            source_levels=source_levels,
            executable=not blockers,
            blockers=blockers,
        )

    @staticmethod
    def _as_dict(value: Any | None) -> dict[str, Any]:
        if value is None:
            return {}
        if isinstance(value, dict):
            return value
        if hasattr(value, "model_dump"):
            return value.model_dump(by_alias=True, exclude_none=True)
        return {}

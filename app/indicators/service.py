from __future__ import annotations

import uuid
from typing import Any, Callable

from app.db.repositories import insert_generated_sql
from app.sqlgen.runner import run_sql_trial
from app.sqlgen.validator import validate_select_sql

from .repository import IndicatorDraftRepository
from .sql_plan import render_indicator_sql


class DraftWorkflowError(RuntimeError):
    pass


class IndicatorDraftWorkflowService:
    def __init__(
        self,
        *,
        runtime_engine: Any,
        business_db: Any,
        draft_repository: IndicatorDraftRepository,
        sql_insert_fn: Callable[..., Any] | None = None,
        trial_fn: Callable[..., dict[str, Any]] | None = None,
    ):
        self.runtime_engine = runtime_engine
        self.business_db = business_db
        self.draft_repository = draft_repository
        self.sql_insert_fn = sql_insert_fn or self._insert_sql
        self.trial_fn = trial_fn or self._trial

    def generate_sql(
        self, draft_id: str, expected_version: int, actor_id: str
    ):
        draft = self.draft_repository.get(draft_id)
        self._require_version(draft, expected_version)
        if draft.status != "metadata_ready":
            raise DraftWorkflowError("只有元数据已确认的设计稿才能生成SQL")
        rendered = render_indicator_sql(draft.sql_plan, draft.field_mapping)
        validation = validate_select_sql(
            rendered["sql_text"], draft.hospital_id, rendered["main_table"]
        )
        if not validation.get("ok"):
            raise DraftWorkflowError(
                f"SQL安全校验未通过：{validation.get('error') or validation.get('message')}"
            )
        sql_id = f"SQL_{uuid.uuid4().hex[:12]}"
        self.sql_insert_fn(
            runtime_engine=self.runtime_engine,
            sql_id=sql_id,
            hospital_id=draft.hospital_id,
            rule_id=draft.proposed_index_code,
            dialect="mysql",
            sql_text=rendered["sql_text"],
            sql_status="validated",
            validation_message=str(validation.get("message") or ""),
            generated_by=f"draft:{draft_id}:v{expected_version}",
        )
        return self.draft_repository.transition(
            draft_id,
            expected_version,
            "sql_ready",
            {
                "current_sql": rendered["sql_text"],
                "sql_params": rendered["params"],
                "sql_id": sql_id,
                "trial_result": {},
                "trial_draft_version": None,
            },
            actor_id,
            "sql_generated",
        )

    def trial_run(
        self,
        draft_id: str,
        expected_version: int,
        stat_start_time: str,
        stat_end_time: str,
        actor_id: str,
    ):
        draft = self.draft_repository.get(draft_id)
        self._require_version(draft, expected_version)
        if draft.status != "sql_ready" or not draft.current_sql or not draft.sql_id:
            raise DraftWorkflowError("请先为当前版本生成并校验SQL")
        trial = self.trial_fn(
            runtime_engine=self.runtime_engine,
            business_db=self.business_db,
            sql_id=draft.sql_id,
            sql_text=draft.current_sql,
            hospital_id=draft.hospital_id,
            rule_id=draft.proposed_index_code,
            stat_start_time=stat_start_time,
            stat_end_time=stat_end_time,
            params=draft.sql_params,
            run_by=actor_id,
        )
        if trial.get("status") != "success":
            raise DraftWorkflowError(
                f"SQL试运行未通过：{trial.get('error_message') or trial.get('status')}"
            )
        next_version = expected_version + 1
        return self.draft_repository.transition(
            draft_id,
            expected_version,
            "trial_passed",
            {
                "trial_result": {
                    **trial,
                    "stat_start_time": stat_start_time,
                    "stat_end_time": stat_end_time,
                },
                "trial_draft_version": next_version,
            },
            actor_id,
            "trial_run",
        )

    def submit(self, draft_id: str, expected_version: int, actor_id: str):
        draft = self.draft_repository.get(draft_id)
        self._require_version(draft, expected_version)
        if (
            draft.status != "trial_passed"
            or draft.trial_result.get("status") != "success"
            or draft.trial_draft_version != draft.current_version
        ):
            raise DraftWorkflowError("只有当前版本SQL试运行通过后才能提交审批")
        return self.draft_repository.transition(
            draft_id,
            expected_version,
            "pending_approval",
            {"trial_draft_version": expected_version + 1},
            actor_id,
            "submitted",
        )

    @staticmethod
    def _require_version(draft: Any, expected_version: int) -> None:
        if draft.current_version != expected_version:
            raise DraftWorkflowError(
                f"设计稿版本已变化：当前为{draft.current_version}，请刷新后重试"
            )

    @staticmethod
    def _insert_sql(**kwargs: Any) -> None:
        insert_generated_sql(
            kwargs["runtime_engine"],
            kwargs["sql_id"],
            kwargs["hospital_id"],
            kwargs["rule_id"],
            kwargs["dialect"],
            kwargs["sql_text"],
            kwargs["sql_status"],
            kwargs["validation_message"],
            kwargs["generated_by"],
        )

    @staticmethod
    def _trial(**kwargs: Any) -> dict[str, Any]:
        return run_sql_trial(
            kwargs["runtime_engine"],
            kwargs["business_db"],
            kwargs["sql_id"],
            kwargs["sql_text"],
            kwargs["hospital_id"],
            kwargs["rule_id"],
            kwargs["stat_start_time"],
            kwargs["stat_end_time"],
            kwargs["params"],
            kwargs["run_by"],
        )

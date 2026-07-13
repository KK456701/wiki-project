"""为元数据同步演示提供可逆的表结构变更。"""

from __future__ import annotations

from typing import Any

from sqlalchemy import Engine, inspect, text

from app.demo_data.writer import validate_demo_database_name


_ACTION_SQL = {
    "add": (
        "ALTER TABLE consult_record "
        "ADD COLUMN consult_priority VARCHAR(16) NULL COMMENT '会诊优先级（元数据演示字段）'"
    ),
    "modify": (
        "ALTER TABLE consult_record "
        "MODIFY COLUMN consult_priority VARCHAR(64) NULL COMMENT '会诊优先级（元数据演示字段）'"
    ),
    "remove": "ALTER TABLE consult_record DROP COLUMN consult_priority",
    "restore": "ALTER TABLE consult_record DROP COLUMN consult_priority",
}


def metadata_drift_sql(action: str) -> str:
    try:
        return _ACTION_SQL[action]
    except KeyError as exc:
        raise ValueError("action 必须是 add、modify、remove 或 restore") from exc


def _priority_column(engine: Engine) -> dict[str, Any] | None:
    for column in inspect(engine).get_columns("consult_record"):
        if column["name"] == "consult_priority":
            return column
    return None


def apply_metadata_drift(engine: Engine, action: str) -> dict[str, Any]:
    validate_demo_database_name(engine.url.database)
    sql = metadata_drift_sql(action)
    before = _priority_column(engine)
    if action == "add" and before is not None:
        return {"action": action, "changed": False, "message": "字段已经存在，无需重复新增"}
    if action == "modify" and before is None:
        raise ValueError("consult_priority 不存在，请先执行 add")
    if action in {"remove", "restore"} and before is None:
        return {"action": action, "changed": False, "message": "字段已不存在，当前就是基线结构"}
    with engine.begin() as connection:
        connection.execute(text(sql))
    after = _priority_column(engine)
    return {
        "action": action,
        "changed": True,
        "before_type": str(before["type"]) if before else None,
        "after_type": str(after["type"]) if after else None,
    }

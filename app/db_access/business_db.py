from __future__ import annotations

import re
import time
from typing import Any, Callable

import sqlparse
from sqlparse import tokens as T

from app.db_access.query_result import QueryResult


def _security_text(sql: str) -> str:
    parts: list[str] = []
    for statement in sqlparse.parse(sql):
        for token in statement.flatten():
            if token.ttype in T.Comment or token.ttype in T.Literal.String:
                parts.append(" ")
            else:
                parts.append(token.value)
    return re.sub(r"\s+", " ", "".join(parts).strip()).lower()


def assert_readonly_query(sql: str) -> None:
    statements = [
        statement
        for statement in sqlparse.parse(sql)
        if str(statement).strip().rstrip(";").strip()
    ]
    if len(statements) != 1 or statements[0].get_type() != "SELECT":
        raise ValueError("业务库 MCP 只允许执行单条 SELECT 查询")

    security_text = _security_text(sql)
    padded = f" {security_text} "
    blocked_keywords = (
        " insert ", " update ", " delete ", " merge ", " exec ",
        " execute ", " drop ", " alter ", " truncate ", " create ",
        " grant ", " revoke ",
    )
    if any(keyword in padded for keyword in blocked_keywords):
        raise ValueError("业务库 MCP 禁止写入、执行过程或结构变更 SQL")
    if re.search(r"\bselect\b[\s\S]*\binto\s+#?\w+", security_text):
        raise ValueError("业务库 MCP 禁止 SELECT INTO")
    if "#" in security_text:
        raise ValueError("业务库 MCP 禁止使用临时表")


class BusinessDBClient:
    def __init__(self, execute_sql: Callable[[str], list[dict[str, Any]]], source_id: str, tool_name: str):
        self._execute_sql = execute_sql
        self.source_id = source_id
        self.tool_name = tool_name

    def _assert_select(self, sql: str) -> None:
        assert_readonly_query(sql)

    def execute_select(self, sql: str) -> QueryResult:
        self._assert_select(sql)
        start = time.perf_counter()
        rows = self._execute_sql(sql)
        duration_ms = int((time.perf_counter() - start) * 1000)
        safe_rows = [dict(row) for row in rows]
        return QueryResult(
            rows=safe_rows,
            row_count=len(safe_rows),
            source=self.source_id,
            tool_name=self.tool_name,
            duration_ms=duration_ms,
        )

    def check_available(self) -> dict[str, Any]:
        result = self.execute_select("SELECT 1 AS ok")
        return {
            "ok": True,
            "source": self.source_id,
            "tool_name": self.tool_name,
            "row_count": result.row_count,
            "duration_ms": result.duration_ms,
        }

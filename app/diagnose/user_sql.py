"""将用户粘贴的 SQL Server 脚本归一化为单条只读查询。"""

from __future__ import annotations

import re
from typing import Any

import sqlparse
from pydantic import BaseModel, Field
from sqlparse import tokens as T

from app.db_access.business_db import assert_readonly_query


class PreparedPastedSql(BaseModel):
    safe_to_execute: bool = False
    query_sql: str = ""
    declared_params: dict[str, Any] = Field(default_factory=dict)
    referenced_databases: list[str] = Field(default_factory=list)
    referenced_schemas: list[str] = Field(default_factory=list)
    blocked_reasons: list[str] = Field(default_factory=list)


_DECLARE = re.compile(
    r"(?is)\bDECLARE\s+@(\w+)\s+[\w\[\]]+(?:\s*\([^;=]+\))?\s*=\s*(N?'(?:''|[^'])*'|NULL|[-+]?\d+(?:\.\d+)?)\s*;"
)
_USE = re.compile(r"(?im)^\s*USE\s+\[?([\w-]+)\]?\s*;\s*")
_IDENT = r"(?:\[[^\]]+\]|[A-Za-z_][A-Za-z0-9_$#]*)"
_TABLE_REF = re.compile(
    rf"(?is)\b(?:FROM|JOIN)\s+({_IDENT}(?:\s*\.\s*{_IDENT}){{0,2}})"
)


def _parse_scalar(raw: str) -> Any:
    value = raw.strip()
    if value.upper() == "NULL":
        return None
    if value.startswith("N'") and value.endswith("'"):
        return value[2:-1].replace("''", "'")
    if value.startswith("'") and value.endswith("'"):
        return value[1:-1].replace("''", "'")
    if re.fullmatch(r"[-+]?\d+", value):
        return int(value)
    if re.fullmatch(r"[-+]?\d+\.\d+", value):
        return float(value)
    return value


def _sql_literal(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def _replace_variables(sql: str, params: dict[str, Any]) -> tuple[str, set[str]]:
    output: list[str] = []
    unresolved: set[str] = set()
    index = 0
    state = "normal"
    while index < len(sql):
        char = sql[index]
        pair = sql[index:index + 2]
        if state == "normal":
            if pair == "--":
                state = "line_comment"
                output.append(pair)
                index += 2
                continue
            if pair == "/*":
                state = "block_comment"
                output.append(pair)
                index += 2
                continue
            if char == "'":
                state = "string"
                output.append(char)
                index += 1
                continue
            if char == "[":
                state = "bracket"
                output.append(char)
                index += 1
                continue
            if char == "@":
                match = re.match(r"@([A-Za-z_][A-Za-z0-9_]*)", sql[index:])
                if match:
                    name = match.group(1)
                    if name in params:
                        output.append(_sql_literal(params[name]))
                    else:
                        output.append(match.group(0))
                        unresolved.add(name)
                    index += len(match.group(0))
                    continue
        elif state == "string":
            if pair == "''":
                output.append(pair)
                index += 2
                continue
            if char == "'":
                state = "normal"
        elif state == "line_comment" and char in "\r\n":
            state = "normal"
        elif state == "block_comment" and pair == "*/":
            output.append(pair)
            index += 2
            state = "normal"
            continue
        elif state == "bracket" and char == "]":
            state = "normal"
        output.append(char)
        index += 1
    return "".join(output), unresolved


def _plain_identifier(value: str) -> str:
    return value.strip().strip("[]")


def _references(query_sql: str) -> tuple[list[str], list[str]]:
    databases: list[str] = []
    schemas: list[str] = []
    for raw in _TABLE_REF.findall(query_sql):
        parts = [_plain_identifier(part) for part in re.split(r"\s*\.\s*", raw)]
        if len(parts) == 3 and parts[0] not in databases:
            databases.append(parts[0])
        if len(parts) >= 2:
            schema = parts[-2]
            if schema not in schemas:
                schemas.append(schema)
    return databases, schemas


def _security_text(sql: str) -> str:
    chunks: list[str] = []
    for statement in sqlparse.parse(sql):
        for token in statement.flatten():
            if token.ttype in T.Comment or token.ttype in T.Literal.String:
                chunks.append(" ")
            else:
                chunks.append(token.value)
    return re.sub(r"\s+", " ", "".join(chunks))


def prepare_pasted_sql(
    sql_text: str,
    *,
    allowed_database: str,
    allowed_schema: str,
) -> PreparedPastedSql:
    result = PreparedPastedSql()
    if not sql_text.strip():
        result.blocked_reasons.append("没有识别到可执行 SQL。")
        return result

    use_databases = _USE.findall(sql_text)
    query_without_use = _USE.sub("", sql_text)
    params = {name: _parse_scalar(value) for name, value in _DECLARE.findall(query_without_use)}
    query = _DECLARE.sub("", query_without_use).strip()
    query = re.sub(r"^\s*;\s*(?=WITH\b)", "", query, flags=re.IGNORECASE)
    result.declared_params = params

    query, unresolved = _replace_variables(query, params)
    databases, schemas = _references(query)
    result.referenced_databases = list(dict.fromkeys(databases))
    result.referenced_schemas = list(dict.fromkeys(schemas))

    for database in use_databases + databases:
        if database.lower() != allowed_database.lower():
            result.blocked_reasons.append(f"SQL 引用了非当前业务数据库：{database}。")
    for schema in schemas:
        if allowed_schema and schema.lower() != allowed_schema.lower():
            result.blocked_reasons.append(f"SQL 引用了非当前业务架构：{schema}。")
    if unresolved:
        result.blocked_reasons.append("SQL 参数未赋值：" + ", ".join(sorted(unresolved)) + "。")

    security_text = _security_text(query).upper()
    if re.search(r"\b(?:EXEC|EXECUTE|MERGE|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE)\b", security_text):
        result.blocked_reasons.append("SQL 包含写入、过程执行或结构变更语句。")
    if "#" in security_text or re.search(r"\bSELECT\b[\s\S]*\bINTO\s+", security_text):
        result.blocked_reasons.append("SQL 使用了临时表或 SELECT INTO。")

    if result.blocked_reasons:
        return result
    try:
        assert_readonly_query(query)
    except ValueError as exc:
        result.blocked_reasons.append(str(exc))
        return result

    result.safe_to_execute = True
    result.query_sql = query
    return result

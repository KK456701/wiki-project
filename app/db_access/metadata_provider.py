"""数据库元数据 Provider 抽象。

DBHub 在本项目中作为外部 MCP sidecar 部署。应用层只依赖这个很小的
Provider 接口，元数据同步和诊断 Agent 就可以在测试替身、SQLAlchemy
直连和 DBHub MCP 之间切换。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from sqlalchemy import Engine, text


@dataclass(frozen=True)
class TableMetadata:
    table_name: str
    table_comment: str = ""
    table_type: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "table_name": self.table_name,
            "table_comment": self.table_comment,
            "table_type": self.table_type,
        }


@dataclass(frozen=True)
class ColumnMetadata:
    table_name: str
    column_name: str
    data_type: str = ""
    column_type: str = ""
    is_nullable: str = ""
    column_key: str = ""
    column_default: str | None = None
    column_comment: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "table_name": self.table_name,
            "column_name": self.column_name,
            "data_type": self.data_type,
            "column_type": self.column_type,
            "is_nullable": self.is_nullable,
            "column_key": self.column_key,
            "column_default": self.column_default,
            "column_comment": self.column_comment,
        }


class MetadataProvider(Protocol):
    source_name: str

    def list_tables(self, db_name: str) -> list[dict[str, Any]]:
        ...

    def list_columns(self, db_name: str, table_name: str | None = None) -> list[dict[str, Any]]:
        ...


class SQLAlchemyMetadataProvider:
    source_name = "sqlalchemy"

    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def list_tables(self, db_name: str) -> list[dict[str, Any]]:
        with self.engine.connect() as conn:
            rows = conn.execute(
                text("SELECT TABLE_NAME, TABLE_COMMENT, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = :db"),
                {"db": db_name},
            ).fetchall()
        return [
            TableMetadata(
                table_name=row[0],
                table_comment=row[1] or "",
                table_type=row[2] or "",
            ).as_dict()
            for row in rows
        ]

    def list_columns(self, db_name: str, table_name: str | None = None) -> list[dict[str, Any]]:
        sql = (
            "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT "
            "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = :db"
        )
        params: dict[str, Any] = {"db": db_name}
        if table_name:
            sql += " AND TABLE_NAME = :table_name"
            params["table_name"] = table_name
        sql += " ORDER BY TABLE_NAME, ORDINAL_POSITION"
        with self.engine.connect() as conn:
            rows = conn.execute(text(sql), params).fetchall()
        return [
            ColumnMetadata(
                table_name=row[0],
                column_name=row[1],
                data_type=row[2] or "",
                column_type=row[3] or "",
                is_nullable=row[4] or "",
                column_key=row[5] or "",
                column_default=None if row[6] is None else str(row[6]),
                column_comment=row[7] or "",
            ).as_dict()
            for row in rows
        ]


class DBHubMetadataProvider:
    """DBHub 元数据 Provider。

    生产部署中，DBHub 通过 MCP 暴露数据库工具。这个 Provider 只接收
    execute_sql 函数，因此具体传输方式可以是 HTTP MCP、测试替身或后续
    更完整的 MCP SDK，不影响同步和诊断代码。
    """

    source_name = "dbhub"

    def __init__(
        self,
        execute_sql,
        *,
        dialect: str = "mysql",
        schema_name: str = "",
    ) -> None:  # type: ignore[no-untyped-def]
        self._execute_sql = execute_sql
        self.dialect = str(dialect or "mysql").lower()
        self.schema_name = str(schema_name or "")
        self.mapped_scope_only = self.dialect == "sqlserver"

    @staticmethod
    def _literal(value: str) -> str:
        return str(value).replace("'", "''")

    def _sqlserver_scope(self, db_name: str) -> str:
        catalog = self._literal(db_name)
        schema = self._literal(self.schema_name or "dbo")
        return (
            f"TABLE_CATALOG = '{catalog}' "
            f"AND TABLE_SCHEMA = '{schema}'"
        )

    def list_tables(self, db_name: str) -> list[dict[str, Any]]:
        if self.dialect == "sqlserver":
            sql = (
                "SELECT TABLE_NAME, '' AS TABLE_COMMENT, TABLE_TYPE "
                "FROM INFORMATION_SCHEMA.TABLES WHERE "
                + self._sqlserver_scope(db_name)
                + " ORDER BY TABLE_NAME"
            )
        else:
            sql = "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME"
        rows = self._execute_sql(sql)
        return [
            TableMetadata(
                table_name=row.get("TABLE_NAME") or row.get("table_name") or "",
                table_comment=row.get("TABLE_COMMENT") or row.get("table_comment") or "",
                table_type=row.get("TABLE_TYPE") or row.get("table_type") or "",
            ).as_dict()
            for row in rows
        ]

    def list_columns(self, db_name: str, table_name: str | None = None) -> list[dict[str, Any]]:
        if self.dialect == "sqlserver":
            sql = (
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, "
                "DATA_TYPE AS COLUMN_TYPE, IS_NULLABLE, '' AS COLUMN_KEY, "
                "COLUMN_DEFAULT, '' AS COLUMN_COMMENT "
                "FROM INFORMATION_SCHEMA.COLUMNS WHERE "
                + self._sqlserver_scope(db_name)
            )
        else:
            sql = (
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, COLUMN_COMMENT "
                "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
            )
        if table_name:
            # 表名来自医院字段映射或固定系统配置，仅用于元数据查询。
            safe_table = str(table_name).replace("'", "''")
            sql += f" AND TABLE_NAME = '{safe_table}'"
        sql += " ORDER BY TABLE_NAME, ORDINAL_POSITION"
        rows = self._execute_sql(sql)
        return [
            ColumnMetadata(
                table_name=row.get("TABLE_NAME") or row.get("table_name") or "",
                column_name=row.get("COLUMN_NAME") or row.get("column_name") or "",
                data_type=row.get("DATA_TYPE") or row.get("data_type") or "",
                column_type=row.get("COLUMN_TYPE") or row.get("column_type") or "",
                is_nullable=row.get("IS_NULLABLE") or row.get("is_nullable") or "",
                column_key=row.get("COLUMN_KEY") or row.get("column_key") or "",
                column_default=row.get("COLUMN_DEFAULT") or row.get("column_default"),
                column_comment=row.get("COLUMN_COMMENT") or row.get("column_comment") or "",
            ).as_dict()
            for row in rows
        ]

"""Migrate the legacy MySQL runtime database to the embedded SQLite store.

The Wiki remains the authority for versioned rule knowledge.  This utility copies
mutable operational records (sessions, traces, audits, evidence, drafts, cached
metadata, and generated SQL objects) so an existing installation can stop running
an external MySQL server without losing history.
"""

from __future__ import annotations

import argparse
import datetime as dt
import decimal
import hashlib
import json
import re
import sqlite3
from pathlib import Path
from typing import Any, Iterable

import pymysql
import yaml
from sqlalchemy.engine import make_url


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / "config.yaml"
DEFAULT_TARGET = ROOT / "runtime" / "wiki_agent_runtime.db"


def _quote(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def _sqlite_type(mysql_type: str) -> str:
    value = mysql_type.lower()
    if value in {"tinyint", "smallint", "mediumint", "int", "integer", "bigint", "bit", "bool", "boolean"}:
        return "INTEGER"
    if value in {"decimal", "numeric", "float", "double", "real"}:
        return "REAL"
    if value in {"binary", "varbinary", "blob", "tinyblob", "mediumblob", "longblob"}:
        return "BLOB"
    return "TEXT"


def _sqlite_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bytes)):
        return value
    if isinstance(value, decimal.Decimal):
        return float(value)
    if isinstance(value, (dt.datetime, dt.date, dt.time)):
        return value.isoformat(sep=" ") if isinstance(value, dt.datetime) else value.isoformat()
    if isinstance(value, bytearray):
        return bytes(value)
    return str(value)


def _mysql_connection(source_url: str):
    url = make_url(source_url)
    if not url.drivername.startswith("mysql"):
        raise ValueError("source URL must be a MySQL SQLAlchemy URL")
    return pymysql.connect(
        host=url.host or "127.0.0.1",
        port=url.port or 3306,
        user=url.username or "root",
        password=url.password or "",
        database=url.database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=False,
    ), str(url.database)


def _rows(cursor, sql: str, params: Iterable[Any] = ()) -> list[dict[str, Any]]:
    cursor.execute(sql, tuple(params))
    return list(cursor.fetchall())


def migrate(source_url: str, target: Path) -> dict[str, Any]:
    target = target.resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".migrating")
    temporary.unlink(missing_ok=True)

    mysql, database = _mysql_connection(source_url)
    sqlite = sqlite3.connect(temporary)
    sqlite.execute("PRAGMA journal_mode=WAL")
    sqlite.execute("PRAGMA synchronous=NORMAL")
    sqlite.execute("PRAGMA foreign_keys=OFF")
    copied: dict[str, int] = {}
    try:
        with mysql.cursor() as cursor:
            tables = _rows(
                cursor,
                """
                SELECT TABLE_NAME
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=%s AND TABLE_TYPE='BASE TABLE'
                ORDER BY TABLE_NAME
                """,
                (database,),
            )
            for table_row in tables:
                table = str(table_row["TABLE_NAME"])
                columns = _rows(
                    cursor,
                    """
                    SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
                           COLUMN_KEY, EXTRA
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s
                    ORDER BY ORDINAL_POSITION
                    """,
                    (database, table),
                )
                primary = [str(c["COLUMN_NAME"]) for c in columns if c.get("COLUMN_KEY") == "PRI"]
                definitions: list[str] = []
                for column in columns:
                    name = str(column["COLUMN_NAME"])
                    definition = f"{_quote(name)} {_sqlite_type(str(column['DATA_TYPE']))}"
                    if len(primary) == 1 and name == primary[0]:
                        if "auto_increment" in str(column.get("EXTRA") or "").lower():
                            definition = f"{_quote(name)} INTEGER PRIMARY KEY AUTOINCREMENT"
                        else:
                            definition += " PRIMARY KEY"
                    if column.get("IS_NULLABLE") == "NO" and "PRIMARY KEY" not in definition:
                        definition += " NOT NULL"
                    definitions.append(definition)
                if len(primary) > 1:
                    definitions.append("PRIMARY KEY (" + ", ".join(_quote(name) for name in primary) + ")")
                sqlite.execute(f"CREATE TABLE {_quote(table)} ({', '.join(definitions)})")

                cursor.execute(f"SELECT * FROM `{table.replace('`', '``')}`")
                names = [str(c["COLUMN_NAME"]) for c in columns]
                placeholders = ", ".join("?" for _ in names)
                insert_sql = (
                    f"INSERT INTO {_quote(table)} ({', '.join(_quote(name) for name in names)}) "
                    f"VALUES ({placeholders})"
                )
                count = 0
                while True:
                    batch = cursor.fetchmany(1000)
                    if not batch:
                        break
                    sqlite.executemany(
                        insert_sql,
                        [tuple(_sqlite_value(row.get(name)) for name in names) for row in batch],
                    )
                    count += len(batch)
                copied[table] = count

                statistics = _rows(
                    cursor,
                    """
                    SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SEQ_IN_INDEX
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s AND INDEX_NAME <> 'PRIMARY'
                    ORDER BY INDEX_NAME, SEQ_IN_INDEX
                    """,
                    (database, table),
                )
                indexes: dict[str, dict[str, Any]] = {}
                for item in statistics:
                    index = indexes.setdefault(
                        str(item["INDEX_NAME"]),
                        {"unique": not bool(item["NON_UNIQUE"]), "columns": []},
                    )
                    index["columns"].append(str(item["COLUMN_NAME"]))
                for name, index in indexes.items():
                    safe_name = re.sub(r"[^A-Za-z0-9_]", "_", f"{table}_{name}")
                    unique = "UNIQUE " if index["unique"] else ""
                    try:
                        sqlite.execute(
                            f"CREATE {unique}INDEX {_quote(safe_name)} ON {_quote(table)} "
                            f"({', '.join(_quote(column) for column in index['columns'])})"
                        )
                    except sqlite3.IntegrityError:
                        # Preserve data even when historical rows violate a legacy unique index.
                        sqlite.execute(
                            f"CREATE INDEX {_quote(safe_name + '_non_unique')} ON {_quote(table)} "
                            f"({', '.join(_quote(column) for column in index['columns'])})"
                        )
        sqlite.commit()
        integrity = sqlite.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"SQLite integrity check failed: {integrity}")
    except Exception:
        sqlite.rollback()
        sqlite.close()
        temporary.unlink(missing_ok=True)
        raise
    finally:
        mysql.close()
    sqlite.close()

    backup = target.with_suffix(target.suffix + ".before-migration")
    backup.unlink(missing_ok=True)
    if target.exists():
        target.replace(backup)
    temporary.replace(target)
    manifest = {
        "source": "legacy_mysql_runtime",
        "target": str(target.relative_to(ROOT) if target.is_relative_to(ROOT) else target),
        "migrated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "table_count": len(copied),
        "row_count": sum(copied.values()),
        "tables": copied,
        "content_fingerprint": hashlib.sha256(
            json.dumps(copied, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest(),
    }
    manifest_path = target.parent / "mysql-to-sqlite-migration.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


def switch_config(config_path: Path) -> None:
    text = config_path.read_text(encoding="utf-8-sig")
    replacement = 'runtime_db_url: "sqlite+pysqlite:///runtime/wiki_agent_runtime.db"'
    text, count = re.subn(r"(?m)^runtime_db_url\s*:\s*.*$", replacement, text, count=1)
    if count != 1:
        raise ValueError("config.yaml does not contain exactly one runtime_db_url")
    company = 'company_db_url: "sqlite+pysqlite:///runtime/wiki_agent_runtime.db"'
    if re.search(r"(?m)^company_db_url\s*:", text):
        text = re.sub(r"(?m)^company_db_url\s*:\s*.*$", company, text, count=1)
    else:
        text = text.replace(replacement, replacement + "\n" + company, 1)
    # The application must not retain a direct hospital database password. DBHub is authoritative.
    text = re.sub(r"(?m)^business_db_url\s*:\s*.*(?:\r?\n)?", "", text)
    config_path.write_text(text, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--source-url")
    parser.add_argument("--target", type=Path, default=DEFAULT_TARGET)
    parser.add_argument("--switch-config", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config = yaml.safe_load(args.config.read_text(encoding="utf-8-sig")) or {}
    source_url = args.source_url or config.get("runtime_db_url")
    if not source_url:
        raise ValueError("legacy runtime_db_url is missing")
    manifest = migrate(str(source_url), args.target)
    if args.switch_config:
        switch_config(args.config)
    print(
        f"Migrated {manifest['table_count']} tables / {manifest['row_count']} rows "
        f"to {manifest['target']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""数据库引擎模块。"""

from functools import lru_cache
from pathlib import Path

from sqlalchemy import Engine, create_engine, event
from app.config import get


PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _default_runtime_url() -> str:
    path = (PROJECT_ROOT / "runtime" / "wiki_agent_runtime.db").as_posix()
    return f"sqlite+pysqlite:///{path}"


@lru_cache(maxsize=8)
def _create_cached_engine(url: str) -> Engine:
    if url.startswith("sqlite"):
        engine = create_engine(
            url,
            pool_pre_ping=True,
            connect_args={"check_same_thread": False, "timeout": 30},
        )

        @event.listens_for(engine, "connect")
        def _configure_sqlite(connection, _record) -> None:
            cursor = connection.cursor()
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA busy_timeout=30000")
            cursor.close()

        return engine
    return create_engine(url, pool_pre_ping=True)


def create_runtime_engine() -> Engine:
    url = get("runtime_db_url", _default_runtime_url())
    return _create_cached_engine(url)


def ensure_embedded_runtime_schema(engine: Engine) -> None:
    """Initialize a fresh embedded runtime store without requiring MySQL."""
    if engine.dialect.name != "sqlite":
        return
    schema_path = PROJECT_ROOT / "scripts" / "init_runtime_sqlite.sql"
    sql = schema_path.read_text(encoding="utf-8-sig")
    raw = engine.raw_connection()
    try:
        raw.executescript(sql)
        raw.commit()
    finally:
        raw.close()


def create_company_engine() -> Engine:
    # 公司包、发布记录与本地运行数据共用内置 SQLite；规则正文仍在 Wiki。
    url = get("company_db_url", get("runtime_db_url", _default_runtime_url()))
    return _create_cached_engine(url)


def create_business_engine() -> Engine:
    url = get("business_db_url", _default_runtime_url())
    return _create_cached_engine(url)

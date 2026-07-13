"""数据库引擎模块。"""

from functools import lru_cache

from sqlalchemy import create_engine, Engine
from app.config import get


@lru_cache(maxsize=8)
def _create_cached_engine(url: str) -> Engine:
    return create_engine(url, pool_pre_ping=True)


def create_runtime_engine() -> Engine:
    url = get("runtime_db_url", "mysql+pymysql://root:123456@127.0.0.1:3306/wiki_agent_runtime?charset=utf8mb4")
    return _create_cached_engine(url)


def create_company_engine() -> Engine:
    url = get("company_db_url", "mysql+pymysql://root:123456@127.0.0.1:3306/wiki_company_kb?charset=utf8mb4")
    return _create_cached_engine(url)


def create_business_engine() -> Engine:
    url = get("business_db_url", "mysql+pymysql://root:123456@127.0.0.1:3306/hospital_demo_data?charset=utf8mb4")
    return _create_cached_engine(url)

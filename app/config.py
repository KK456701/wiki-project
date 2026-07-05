"""配置加载器。优先级：环境变量 > config.yaml > 代码默认值。"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml

_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config.yaml"
_cache: dict[str, Any] | None = None


def _load_config() -> dict[str, Any]:
    global _cache
    if _cache is not None:
        return _cache
    if _CONFIG_PATH.exists():
        with open(_CONFIG_PATH, encoding="utf-8") as f:
            _cache = yaml.safe_load(f) or {}
    else:
        _cache = {}
    return _cache


def get(key: str, default: Any = "") -> str:
    """读取配置，环境变量优先。"""
    env_key = key.upper()
    env_val = os.getenv(env_key)
    if env_val is not None:
        return str(env_val)
    return str(_load_config().get(key, default))


def get_bool(key: str, default: bool = False) -> bool:
    val = get(key, str(default)).strip().lower()
    return val in ("true", "1", "yes", "on")


def get_int(key: str, default: int = 0) -> int:
    try:
        return int(get(key, str(default)))
    except (ValueError, TypeError):
        return default

"""用现有 config.yaml 安全启动 Java 迁移运行时，不输出连接凭据。"""

from __future__ import annotations

import argparse
import os
import subprocess
import zipfile
from pathlib import Path
from typing import Mapping
from urllib.parse import unquote, urlparse

import yaml


ROOT = Path(__file__).resolve().parents[1]
TEMURIN_HOME_ROOT = Path(r"F:\kaifa\temurin17")
LEGACY_JAVA_HOME = Path(r"F:\kaifa\jdk")
VUE_INDEX_ENTRY = "BOOT-INF/classes/static/index.html"


def preferred_java_home() -> Path:
    candidates = sorted(TEMURIN_HOME_ROOT.glob("jdk-17*"), reverse=True)
    return candidates[0] if candidates else LEGACY_JAVA_HOME


def append_java_proxy_options(
    environment: dict[str, str], proxy_url: object
) -> None:
    parsed = urlparse(str(proxy_url or ""))
    if not parsed.hostname:
        return
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    proxy_options = (
        f"-Dhttp.proxyHost={parsed.hostname} -Dhttp.proxyPort={port} "
        f"-Dhttps.proxyHost={parsed.hostname} -Dhttps.proxyPort={port} "
        '-Dhttp.nonProxyHosts="localhost|127.*"'
    )
    existing = environment.get("JAVA_TOOL_OPTIONS", "").strip()
    environment["JAVA_TOOL_OPTIONS"] = " ".join(
        option for option in (existing, proxy_options) if option
    )


def build_java_environment(
    config: Mapping[str, object], base_environment: Mapping[str, str]
) -> dict[str, str]:
    environment = dict(base_environment)
    runtime_value = str(config.get("runtime_db_url") or "")
    runtime_url = urlparse(runtime_value)
    if not runtime_url.scheme:
        raise ValueError("config.yaml 缺少有效 runtime_db_url")
    if runtime_url.scheme.startswith("sqlite"):
        raw_path = runtime_value.split("///", 1)[-1]
        database_path = Path(unquote(raw_path))
        if not database_path.is_absolute():
            database_path = ROOT / database_path
        database_path.parent.mkdir(parents=True, exist_ok=True)
        environment.setdefault("WIKI_RUNTIME_DB_URL", f"jdbc:sqlite:{database_path.resolve()}")
        environment.setdefault("WIKI_RUNTIME_DB_USER", "")
        environment.setdefault("WIKI_RUNTIME_DB_PASSWORD", "")
    else:
        if not runtime_url.hostname or not runtime_url.path.strip("/"):
            raise ValueError("config.yaml 缺少有效 runtime_db_url")
        database = runtime_url.path.strip("/")
        port = runtime_url.port or 3306
        environment.setdefault(
            "WIKI_RUNTIME_DB_URL",
            "jdbc:mysql://"
            f"{runtime_url.hostname}:{port}/{database}"
            "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
        )
        environment.setdefault("WIKI_RUNTIME_DB_USER", unquote(runtime_url.username or "root"))
        environment.setdefault("WIKI_RUNTIME_DB_PASSWORD", unquote(runtime_url.password or ""))
    environment.setdefault("WIKI_KNOWLEDGE_ROOT", str(ROOT / "core-rules-wiki"))
    environment.setdefault("WIKI_ADMIN_PASSWORD", str(config.get("admin_password") or ""))

    source_id = str(config.get("business_db_source_id") or "win60_qa_991827")
    source_suffix = source_id.upper()
    environment.setdefault(f"DBHUB_SOURCE_ID_{source_suffix}", source_id)
    environment.setdefault(
        f"DBHUB_EXECUTE_TOOL_{source_suffix}",
        str(config.get(f"dbhub_execute_tool_{source_id}") or f"execute_sql_{source_id}"),
    )
    environment.setdefault("JAVA_HOME", str(preferred_java_home()))
    java_bin = str(Path(environment["JAVA_HOME"]) / "bin")
    environment["PATH"] = java_bin + os.pathsep + environment.get("PATH", "")
    append_java_proxy_options(environment, config.get("java_http_proxy_url"))

    if not runtime_url.scheme.startswith("sqlite") and not environment["WIKI_RUNTIME_DB_PASSWORD"]:
        raise ValueError("运行库密码未配置")
    if not environment["WIKI_ADMIN_PASSWORD"]:
        raise ValueError("管理员密码未配置")
    return environment


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="使用现有本地配置启动 Java 迁移运行时")
    parser.add_argument("--mode", choices=("Shadow", "Authority"), default="Shadow")
    parser.add_argument("--config", type=Path, default=ROOT / "config.yaml")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--readiness-report", type=Path)
    parser.add_argument("--port", type=int)
    parser.add_argument("--confirm-cutover", action="store_true")
    return parser.parse_args()


def validate_authority_jar(jar_path: Path) -> Path:
    """Authority JAR must contain the Vue entry page, not only backend classes."""
    resolved = jar_path if jar_path.is_absolute() else ROOT / jar_path
    resolved = resolved.resolve()
    if not resolved.is_file():
        raise ValueError(f"Java JAR 不存在: {resolved}")
    try:
        with zipfile.ZipFile(resolved) as archive:
            if VUE_INDEX_ENTRY not in archive.namelist():
                raise ValueError(
                    "权威 Java JAR 未包含 Vue 页面，请使用 scripts/build-java-vue.ps1 重新构建。"
                )
    except zipfile.BadZipFile as exception:
        raise ValueError(f"Java JAR 无效: {resolved}") from exception
    return resolved


def main() -> int:
    args = parse_args()
    config = yaml.safe_load(args.config.read_text(encoding="utf-8-sig")) or {}
    environment = build_java_environment(config, os.environ)
    if args.mode == "Authority" and args.jar:
        args.jar = validate_authority_jar(args.jar)
    command = [
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        str(ROOT / "scripts" / "start-java-runtime.ps1"), "-Mode", args.mode,
    ]
    if args.jar:
        command.extend(("-JarPath", str(args.jar)))
    if args.readiness_report:
        command.extend(("-ReadinessReport", str(args.readiness_report)))
    if args.port:
        command.extend(("-Port", str(args.port)))
    if args.confirm_cutover:
        command.append("-ConfirmCutover")
    return subprocess.run(command, cwd=ROOT, env=environment, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())

"""比较 FastAPI 与 Java 影子服务的规则只读响应，不记录登录令牌。"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


SAFE_EFFECTIVE_FIELDS = (
    "rule_id",
    "rule_name",
    "category",
    "hospital_id",
    "effective_level",
    "definition",
    "formula",
    "numerator_rule",
    "denominator_rule",
    "filter_rule",
    "exclude_rule",
    "field_status",
    "sql_status",
    "national_version",
    "hospital_version",
    "overridden_fields",
    "fallback_chain",
    "rule_source",
    "warnings",
)


def request_json(
    method: str,
    url: str,
    *,
    token: str | None = None,
    body: dict[str, Any] | None = None,
) -> Any:
    headers = {"Accept": "application/json"}
    payload = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def project_effective(payload: dict[str, Any]) -> dict[str, Any]:
    return {field: payload.get(field) for field in SAFE_EFFECTIVE_FIELDS}


def main() -> int:
    token = os.environ.get("MIGRATION_HOSPITAL_TOKEN", "").strip()
    hospital_id = os.environ.get("MIGRATION_HOSPITAL_ID", "").strip()
    query = os.environ.get("MIGRATION_RULE_QUERY", "急会诊及时到位率").strip()
    python_base = os.environ.get("PYTHON_AGENT_URL", "http://127.0.0.1:8765").rstrip("/")
    java_base = os.environ.get("JAVA_AGENT_URL", "http://127.0.0.1:8766").rstrip("/")
    if not token or not hospital_id:
        print("请设置 MIGRATION_HOSPITAL_TOKEN 和 MIGRATION_HOSPITAL_ID。", file=sys.stderr)
        return 2

    encoded_query = urllib.parse.quote(query)
    java_search = request_json(
        "GET", f"{java_base}/api/kb/rules/search?query={encoded_query}&limit=5", token=token
    )
    python_search = request_json(
        "POST", f"{python_base}/api/kb/search", body={"query": query, "limit": 5}
    )
    java_rule_id = java_search.get("resolved_rule_id")
    python_rule_id = python_search.get("resolved_rule_id")
    if java_rule_id != python_rule_id:
        print(json.dumps(
            {
                "status": "different",
                "field": "resolved_rule_id",
                "python": python_rule_id,
                "java": java_rule_id,
            },
            ensure_ascii=False,
            indent=2,
        ))
        return 1

    encoded_rule = urllib.parse.quote(str(java_rule_id), safe="")
    encoded_hospital = urllib.parse.quote(hospital_id, safe="")
    java_effective = request_json(
        "GET",
        f"{java_base}/api/kb/rules/{encoded_rule}/effective?hospital_id={encoded_hospital}",
        token=token,
    )
    python_effective = request_json(
        "GET",
        f"{python_base}/api/kb/rules/{encoded_rule}/effective?hospital_id={encoded_hospital}",
    )
    java_safe = project_effective(java_effective)
    python_safe = project_effective(python_effective)
    if java_safe != python_safe:
        differences = {
            key: {"python": python_safe.get(key), "java": java_safe.get(key)}
            for key in SAFE_EFFECTIVE_FIELDS
            if python_safe.get(key) != java_safe.get(key)
        }
        print(json.dumps(
            {"status": "different", "differences": differences},
            ensure_ascii=False,
            indent=2,
        ))
        return 1
    print(json.dumps(
        {"status": "same", "rule_id": java_rule_id, "hospital_id": hospital_id},
        ensure_ascii=False,
    ))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (urllib.error.URLError, TimeoutError) as exc:
        print(f"双跑服务不可用：{exc}", file=sys.stderr)
        raise SystemExit(3) from exc

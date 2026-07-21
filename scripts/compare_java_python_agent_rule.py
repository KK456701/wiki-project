"""手工双跑 Python 与 Java 规则解释 Agent，不打印登录令牌。"""

from __future__ import annotations

import argparse
import json
from urllib import request


def post_json(url: str, token: str, payload: dict) -> dict:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    call = request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
        },
    )
    with request.urlopen(call, timeout=300) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="双跑规则解释 Agent")
    parser.add_argument("--token", required=True, help="现有医院登录 Bearer token")
    parser.add_argument("--python-url", default="http://127.0.0.1:8765")
    parser.add_argument("--java-url", default="http://127.0.0.1:8766")
    parser.add_argument("--query", default="急会诊及时到位率怎么算？")
    parser.add_argument("--model-id")
    args = parser.parse_args()

    payload = {"query": args.query, "session_id": "migration_rule_shadow"}
    if args.model_id:
        payload["model_id"] = args.model_id
    python_result = post_json(
        args.python_url.rstrip("/") + "/api/agent/chat", args.token, payload
    )
    java_result = post_json(
        args.java_url.rstrip("/") + "/api/migration/agent/chat", args.token, payload
    )
    required = {"answer", "stop_reason", "trace_id", "session_id", "step_count"}
    report = {
        "contract_fields_equal": set(python_result) == set(java_result) == required,
        "python_stop_reason": python_result.get("stop_reason"),
        "java_stop_reason": java_result.get("stop_reason"),
        "python_step_count": python_result.get("step_count"),
        "java_step_count": java_result.get("step_count"),
        "python_answer": python_result.get("answer"),
        "java_answer": java_result.get("answer"),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["contract_fields_equal"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

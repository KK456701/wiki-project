"""只读双跑 FastAPI 与 Java，并生成不含令牌和患者数据的切流验收报告。"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SUITE = ROOT / "contracts" / "migration" / "v1" / "cutover-suite.json"
DEFAULT_OUTPUT = ROOT / "runtime" / "cutover-readiness.json"
PROTOCOL_MARKERS = ("<｜｜DSML｜｜", "tool_calls>", "<function=", "<tool_call>")
SAFE_RULE_FIELDS = (
    "rule_id", "rule_name", "category", "hospital_id", "effective_level",
    "definition", "formula", "numerator_rule", "denominator_rule",
    "filter_rule", "exclude_rule", "field_status", "sql_status",
    "national_version", "hospital_version", "overridden_fields",
)


class CheckFailure(RuntimeError):
    pass


def request_json(
    method: str,
    url: str,
    *,
    token: str | None = None,
    hospital_token: str | None = None,
    body: dict[str, Any] | None = None,
    timeout: int = 30,
) -> Any:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if hospital_token:
        headers["X-Hospital-Authorization"] = f"Bearer {hospital_token}"
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    call = urllib.request.Request(url, method=method, headers=headers, data=payload)
    try:
        with urllib.request.urlopen(call, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exception:
        raise CheckFailure(f"HTTP_{exception.code}") from exception
    except (urllib.error.URLError, TimeoutError) as exception:
        raise CheckFailure("SERVICE_UNAVAILABLE") from exception


def canonical_rule(payload: dict[str, Any]) -> dict[str, Any]:
    return {field: payload.get(field) for field in SAFE_RULE_FIELDS}


def canonical_drafts(payload: Any) -> list[dict[str, Any]]:
    rows = payload if isinstance(payload, list) else payload.get("items", [])
    return sorted(
        [
            {
                "draft_id": row.get("draft_id"),
                "status": row.get("status"),
                "current_version": row.get("current_version"),
                "formal_index_code": row.get("formal_index_code"),
            }
            for row in rows
            if isinstance(row, dict)
        ],
        key=lambda row: str(row.get("draft_id") or ""),
    )


def canonical_concepts(payload: Any) -> list[str]:
    rows = payload.get("items", payload.get("concepts", [])) if isinstance(payload, dict) else []
    return sorted(
        str(row.get("concept_code") or row.get("code") or "")
        for row in rows
        if isinstance(row, dict) and (row.get("concept_code") or row.get("code"))
    )


def canonical_plan_ids(payload: Any) -> list[str]:
    rows = payload if isinstance(payload, list) else payload.get("items", [])
    return sorted(
        str(item.get("plan_id"))
        for item in rows
        if isinstance(item, dict) and item.get("plan_id")
    )


def safe_agent_result(payload: dict[str, Any]) -> dict[str, Any]:
    answer = str(payload.get("answer") or "").strip()
    return {
        "contract_ok": all(
            key in payload for key in ("answer", "stop_reason", "trace_id", "session_id", "step_count")
        ),
        "stop_reason": payload.get("stop_reason"),
        "answer_present": bool(answer),
        "protocol_clean": not any(marker in answer for marker in PROTOCOL_MARKERS),
        "step_count_valid": isinstance(payload.get("step_count"), int)
        and int(payload.get("step_count")) >= 0,
    }


class ReadinessRunner:
    def __init__(
        self,
        *,
        python_url: str,
        java_url: str,
        hospital_id: str,
        hospital_token: str,
        python_admin_token: str,
        java_admin_token: str,
        model_id: str | None,
        suite: dict[str, Any],
        include_agent: bool,
    ) -> None:
        self.python_url = python_url.rstrip("/")
        self.java_url = java_url.rstrip("/")
        self.hospital_id = hospital_id
        self.hospital_token = hospital_token
        self.python_admin_token = python_admin_token
        self.java_admin_token = java_admin_token
        self.model_id = model_id
        self.suite = suite
        self.include_agent = include_agent
        self.checks: list[dict[str, Any]] = []

    def run(self) -> list[dict[str, Any]]:
        self.check("service_health", "platform", self.service_health)
        self.check("java_feature_manifest", "platform", self.java_feature_manifest)
        self.check("model_capabilities", "model", self.model_capabilities)
        for query in self.suite.get("rule_queries", []):
            self.check(f"rule:{query}", "rule", lambda query=query: self.rule(query))
        self.check("metadata_overview", "workbench", self.metadata_overview)
        self.check("indicator_drafts", "workbench", self.indicator_drafts)
        self.check("terminology_concepts", "workbench", self.terminology_concepts)
        if self.python_admin_token and self.java_admin_token:
            self.check("monitoring_plans", "workbench", self.monitoring_plans)
        else:
            self.skip(
                "monitoring_plans", "workbench",
                "MIGRATION_PYTHON_ADMIN_TOKEN_AND_MIGRATION_JAVA_ADMIN_TOKEN_REQUIRED",
            )
        if self.include_agent:
            for case in self.suite.get("agent_cases", []):
                self.check(
                    f"agent:{case.get('id')}", "agent",
                    lambda case=case: self.agent_case(case),
                )
        else:
            for case in self.suite.get("agent_cases", []):
                self.skip(f"agent:{case.get('id')}", "agent", "AGENT_CHECK_NOT_REQUESTED")
        return self.checks

    def check(self, check_id: str, category: str, action: Callable[[], dict[str, Any]]) -> None:
        started = dt.datetime.now(dt.timezone.utc)
        try:
            details = action()
            status = "passed"
        except CheckFailure as exception:
            details = {"reason_code": str(exception)}
            status = "failed"
        duration = int((dt.datetime.now(dt.timezone.utc) - started).total_seconds() * 1000)
        self.checks.append({
            "check_id": check_id,
            "category": category,
            "status": status,
            "duration_ms": duration,
            "details": details,
        })

    def skip(self, check_id: str, category: str, reason: str) -> None:
        self.checks.append({
            "check_id": check_id, "category": category, "status": "skipped",
            "duration_ms": 0, "details": {"reason_code": reason},
        })

    def service_health(self) -> dict[str, Any]:
        python = request_json("GET", self.python_url + "/api/health")
        java = request_json("GET", self.java_url + "/api/health")
        if str(java.get("status", "")).lower() != "ok":
            raise CheckFailure("JAVA_HEALTH_NOT_OK")
        return {"python_reachable": isinstance(python, dict), "java_status": java.get("status")}

    def java_feature_manifest(self) -> dict[str, Any]:
        payload = request_json("GET", self.java_url + "/api/migration/status")
        completed = set(payload.get("completed", []))
        missing = sorted(set(self.suite.get("required_java_features", [])) - completed)
        if missing:
            raise CheckFailure("JAVA_FEATURES_MISSING:" + ",".join(missing))
        if payload.get("java_runtime") != "compatibility_shadow":
            raise CheckFailure("JAVA_NOT_IN_SHADOW_MODE")
        return {"completed_count": len(completed), "shadow_mode": True}

    def model_capabilities(self) -> dict[str, Any]:
        python = request_json(
            "GET", self.python_url + "/api/agent/capabilities", token=self.hospital_token
        )
        java = request_json(
            "GET", self.java_url + "/api/agent/capabilities", token=self.hospital_token
        )
        python_ids = {str(item.get("id")) for item in python.get("models", [])}
        java_ids = {str(item.get("id")) for item in java.get("models", [])}
        if python_ids != java_ids:
            raise CheckFailure("MODEL_REGISTRY_DIFFERENT")
        if self.model_id and self.model_id not in java_ids:
            raise CheckFailure("REQUESTED_MODEL_MISSING")
        return {"model_ids": sorted(java_ids), "selected_model": self.model_id}

    def rule(self, query: str) -> dict[str, Any]:
        encoded = urllib.parse.quote(query)
        java_search = request_json(
            "GET", f"{self.java_url}/api/kb/rules/search?query={encoded}&limit=5",
            token=self.hospital_token,
        )
        python_search = request_json(
            "POST", self.python_url + "/api/kb/search", body={"query": query, "limit": 5}
        )
        rule_id = java_search.get("resolved_rule_id")
        if not rule_id or rule_id != python_search.get("resolved_rule_id"):
            raise CheckFailure("RULE_RESOLUTION_DIFFERENT")
        rule = urllib.parse.quote(str(rule_id), safe="")
        hospital = urllib.parse.quote(self.hospital_id, safe="")
        java = request_json(
            "GET", f"{self.java_url}/api/kb/rules/{rule}/effective?hospital_id={hospital}",
            token=self.hospital_token,
        )
        python = request_json(
            "GET", f"{self.python_url}/api/kb/rules/{rule}/effective?hospital_id={hospital}"
        )
        if canonical_rule(java) != canonical_rule(python):
            raise CheckFailure("EFFECTIVE_RULE_DIFFERENT")
        return {"rule_id": rule_id, "safe_fields_equal": True}

    def metadata_overview(self) -> dict[str, Any]:
        query = urllib.parse.urlencode({"hospital_id": self.hospital_id})
        python = request_json(
            "GET", f"{self.python_url}/api/metadata/overview?{query}", token=self.hospital_token
        )
        java = request_json(
            "GET", f"{self.java_url}/api/metadata/overview?{query}", token=self.hospital_token
        )
        fields = ("has_snapshot", "table_count", "column_count", "batch_id")
        if {key: python.get(key) for key in fields} != {key: java.get(key) for key in fields}:
            raise CheckFailure("METADATA_OVERVIEW_DIFFERENT")
        return {key: java.get(key) for key in fields}

    def indicator_drafts(self) -> dict[str, Any]:
        query = urllib.parse.urlencode({"hospital_id": self.hospital_id})
        python = request_json(
            "GET", f"{self.python_url}/api/indicator-drafts?{query}", token=self.hospital_token
        )
        java = request_json(
            "GET", f"{self.java_url}/api/indicator-drafts?{query}", token=self.hospital_token
        )
        if canonical_drafts(python) != canonical_drafts(java):
            raise CheckFailure("INDICATOR_DRAFTS_DIFFERENT")
        return {"draft_count": len(canonical_drafts(java))}

    def terminology_concepts(self) -> dict[str, Any]:
        query = urllib.parse.urlencode({"query": "", "limit": "100"})
        python = request_json(
            "GET", f"{self.python_url}/api/terminology/concepts?{query}", token=self.hospital_token
        )
        java = request_json(
            "GET", f"{self.java_url}/api/terminology/concepts?{query}", token=self.hospital_token
        )
        if canonical_concepts(python) != canonical_concepts(java):
            raise CheckFailure("TERMINOLOGY_CONCEPTS_DIFFERENT")
        return {"concept_count": len(canonical_concepts(java))}

    def monitoring_plans(self) -> dict[str, Any]:
        query = urllib.parse.urlencode({"hospital_id": self.hospital_id})
        python = request_json(
            "GET", f"{self.python_url}/api/monitoring/plans?{query}",
            token=self.python_admin_token, hospital_token=self.hospital_token,
        )
        java = request_json(
            "GET", f"{self.java_url}/api/monitoring/plans?{query}",
            token=self.java_admin_token, hospital_token=self.hospital_token,
        )
        python_ids = canonical_plan_ids(python)
        java_ids = canonical_plan_ids(java)
        if python_ids != java_ids:
            raise CheckFailure("MONITORING_PLANS_DIFFERENT")
        return {"plan_count": len(java_ids)}

    def agent_case(self, case: dict[str, Any]) -> dict[str, Any]:
        case_id = str(case.get("id") or "case")
        payload = {
            "query": str(case.get("query") or ""),
            "session_id": f"cutover_{case_id}_{secrets.token_hex(4)}",
        }
        if self.model_id:
            payload["model_id"] = self.model_id
        python = request_json(
            "POST", self.python_url + "/api/agent/chat", token=self.hospital_token,
            body=payload, timeout=300,
        )
        payload["session_id"] = f"cutover_java_{case_id}_{secrets.token_hex(4)}"
        java = request_json(
            "POST", self.java_url + "/api/agent/chat", token=self.hospital_token,
            body=payload, timeout=300,
        )
        python_safe = safe_agent_result(python)
        java_safe = safe_agent_result(java)
        for result in (python_safe, java_safe):
            if not all((
                result["contract_ok"], result["answer_present"],
                result["protocol_clean"], result["step_count_valid"],
                result["stop_reason"] == "final_answer",
            )):
                raise CheckFailure("AGENT_CONTRACT_OR_COMPLETION_FAILED")
        return {"python": python_safe, "java": java_safe}


def build_report(
    runner: ReadinessRunner,
    checks: list[dict[str, Any]],
    *,
    suite_version: str,
    allow_skips: bool,
) -> dict[str, Any]:
    counts = {
        status: sum(1 for item in checks if item["status"] == status)
        for status in ("passed", "failed", "skipped")
    }
    ready = counts["failed"] == 0 and (allow_skips or counts["skipped"] == 0)
    generated = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    report_id = "CUTOVER_" + generated.strftime("%Y%m%dT%H%M%SZ")
    return {
        "schema_version": "java-cutover-readiness-v1",
        "suite_version": suite_version,
        "report_id": report_id,
        "generated_at": generated.isoformat().replace("+00:00", "Z"),
        "status": "ready" if ready else "not_ready",
        "hospital_id": runner.hospital_id,
        "python_url": runner.python_url,
        "java_url": runner.java_url,
        "summary": counts,
        "checks": checks,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成 Java 正式切流前只读双跑验收报告")
    parser.add_argument("--python-url", default=os.environ.get("PYTHON_AGENT_URL", "http://127.0.0.1:8765"))
    parser.add_argument("--java-url", default=os.environ.get("JAVA_AGENT_URL", "http://127.0.0.1:8766"))
    parser.add_argument("--hospital-id", default=os.environ.get("MIGRATION_HOSPITAL_ID", ""))
    parser.add_argument("--token", default=os.environ.get("MIGRATION_HOSPITAL_TOKEN", ""))
    parser.add_argument(
        "--python-admin-token", default=os.environ.get("MIGRATION_PYTHON_ADMIN_TOKEN", "")
    )
    parser.add_argument(
        "--java-admin-token", default=os.environ.get("MIGRATION_JAVA_ADMIN_TOKEN", "")
    )
    parser.add_argument("--model-id", default=os.environ.get("MIGRATION_MODEL_ID", ""))
    parser.add_argument("--suite", type=Path, default=DEFAULT_SUITE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--include-agent", action="store_true")
    parser.add_argument("--allow-skips", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.hospital_id or not args.token:
        print("请设置 MIGRATION_HOSPITAL_ID 和 MIGRATION_HOSPITAL_TOKEN。", file=sys.stderr)
        return 2
    suite = json.loads(args.suite.read_text(encoding="utf-8"))
    runner = ReadinessRunner(
        python_url=args.python_url,
        java_url=args.java_url,
        hospital_id=args.hospital_id,
        hospital_token=args.token,
        python_admin_token=args.python_admin_token,
        java_admin_token=args.java_admin_token,
        model_id=args.model_id or None,
        suite=suite,
        include_agent=args.include_agent,
    )
    report = build_report(
        runner, runner.run(), suite_version=str(suite.get("schema_version") or ""),
        allow_skips=args.allow_skips,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "report_id": report["report_id"], "status": report["status"],
        "summary": report["summary"], "output": str(args.output.resolve()),
    }, ensure_ascii=False))
    return 0 if report["status"] == "ready" else 1


if __name__ == "__main__":
    raise SystemExit(main())


from __future__ import annotations

import io
import json
import re
import shutil
import uuid
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml

from app.kb.tools import DEFAULT_KB_ROOT, KnowledgeBaseTools


class MergeError(RuntimeError):
    pass


MAX_ZIP_BYTES = 20 * 1024 * 1024
MAX_EXTRACTED_BYTES = 80 * 1024 * 1024
ALLOWED_SUFFIXES = {".yaml", ".yml", ".json", ".md", ".txt", ".j2", ".sql"}


def create_merge_report(kb_root: str | Path, zip_bytes: bytes, uploaded_by: str = "admin") -> dict[str, Any]:
    root = Path(kb_root)
    if len(zip_bytes) > MAX_ZIP_BYTES:
        raise MergeError("ZIP_TOO_LARGE")
    report_id = f"MR_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
    report_dir = root / "merge-reports" / report_id
    source_dir = report_dir / "source"
    source_dir.mkdir(parents=True, exist_ok=False)
    _safe_extract_zip(zip_bytes, source_dir)

    manifest = _load_yaml(source_dir / "manifest.yaml")
    hospital_id = str(manifest.get("hospital_id") or "").strip()
    if not hospital_id:
        raise MergeError("HOSPITAL_ID_MISSING_IN_MANIFEST")

    items = []
    items.extend(_diff_overrides(root, source_dir, hospital_id))
    items.extend(_diff_mappings(source_dir, hospital_id, items))
    items.extend(_diff_new_indicators(source_dir))
    summary = _summarize(items)
    report = {
        "report_id": report_id,
        "hospital_id": hospital_id,
        "uploaded_at": datetime.now().isoformat(timespec="seconds"),
        "uploaded_by": uploaded_by,
        "status": "pending_review",
        "summary": summary,
        "items": items,
    }
    _write_report(report_dir, report)
    _append_audit(report_dir, f"upload by={uploaded_by} hospital_id={hospital_id} items={len(items)}")
    return report


def read_merge_report(kb_root: str | Path, report_id: str) -> dict[str, Any]:
    path = Path(kb_root) / "merge-reports" / _safe_id(report_id) / "report.json"
    if not path.exists():
        raise MergeError(f"MERGE_REPORT_NOT_FOUND: {report_id}")
    return json.loads(path.read_text(encoding="utf-8"))


def list_merge_reports(kb_root: str | Path) -> list[dict[str, Any]]:
    root = Path(kb_root) / "merge-reports"
    if not root.exists():
        return []
    reports = []
    for report_path in sorted(root.glob("MR_*/report.json"), reverse=True):
        report = json.loads(report_path.read_text(encoding="utf-8"))
        reports.append({
            "report_id": report.get("report_id"),
            "hospital_id": report.get("hospital_id"),
            "uploaded_at": report.get("uploaded_at"),
            "uploaded_by": report.get("uploaded_by"),
            "status": report.get("status"),
            "summary": report.get("summary", {}),
        })
    return reports


def approve_merge_item(kb_root: str | Path, report_id: str, item_id: str, decision: str, approver_id: str = "admin") -> dict[str, Any]:
    if decision not in {"adopt_as_company_candidate", "keep_as_hospital_local"}:
        raise MergeError("INVALID_MERGE_DECISION")
    root = Path(kb_root)
    report_dir = root / "merge-reports" / _safe_id(report_id)
    report = read_merge_report(root, report_id)
    item = _find_item(report, item_id)
    now = datetime.now().isoformat(timespec="seconds")
    item["status"] = "approved_candidate" if decision == "adopt_as_company_candidate" else "kept_hospital_local"
    item["decision"] = decision
    item["approved_at"] = now
    item["approver_id"] = approver_id
    if decision == "adopt_as_company_candidate":
        candidate_dir = report_dir / "candidates"
        candidate_dir.mkdir(parents=True, exist_ok=True)
        (candidate_dir / f"{_safe_id(item_id)}.json").write_text(json.dumps(item, ensure_ascii=False, indent=2), encoding="utf-8")
    _refresh_report_status(report)
    _write_report(report_dir, report)
    _append_audit(report_dir, f"approve item={item_id} decision={decision} approver={approver_id}")
    return {"report_id": report_id, "item_id": item_id, "status": item["status"], "decision": decision, "approved_at": now, "approver_id": approver_id}


def reject_merge_item(kb_root: str | Path, report_id: str, item_id: str, reason: str = "", approver_id: str = "admin") -> dict[str, Any]:
    root = Path(kb_root)
    report_dir = root / "merge-reports" / _safe_id(report_id)
    report = read_merge_report(root, report_id)
    item = _find_item(report, item_id)
    now = datetime.now().isoformat(timespec="seconds")
    item["status"] = "rejected"
    item["decision"] = "reject"
    item["reject_reason"] = reason
    item["rejected_at"] = now
    item["approver_id"] = approver_id
    _refresh_report_status(report)
    _write_report(report_dir, report)
    _append_audit(report_dir, f"reject item={item_id} approver={approver_id} reason={reason}")
    return {"report_id": report_id, "item_id": item_id, "status": "rejected", "rejected_at": now, "approver_id": approver_id}


def _safe_extract_zip(zip_bytes: bytes, target_dir: Path) -> None:
    total_size = 0
    try:
        with zipfile.ZipFile(io.BytesIO(zip_bytes), "r") as zf:
            for info in zf.infolist():
                name = info.filename.replace("\\", "/")
                if not name or name.endswith("/"):
                    continue
                if name.startswith("/") or ".." in Path(name).parts:
                    raise MergeError("UNSAFE_ZIP_PATH")
                suffix = Path(name).suffix.lower()
                if suffix and suffix not in ALLOWED_SUFFIXES:
                    raise MergeError(f"UNSUPPORTED_FILE_TYPE: {name}")
                total_size += int(info.file_size or 0)
                if total_size > MAX_EXTRACTED_BYTES:
                    raise MergeError("ZIP_EXTRACTED_CONTENT_TOO_LARGE")
                dest = target_dir / name
                resolved = dest.resolve()
                if target_dir.resolve() not in resolved.parents and resolved != target_dir.resolve():
                    raise MergeError("UNSAFE_ZIP_PATH")
                dest.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info, "r") as src, dest.open("wb") as out:
                    shutil.copyfileobj(src, out)
    except zipfile.BadZipFile as exc:
        raise MergeError("INVALID_ZIP_FILE") from exc


def _diff_overrides(root: Path, source_dir: Path, hospital_id: str) -> list[dict[str, Any]]:
    override_dir = source_dir / "overrides"
    if not override_dir.exists():
        return []
    tools = KnowledgeBaseTools(root)
    items: list[dict[str, Any]] = []
    for index, path in enumerate(sorted(override_dir.glob("*.yaml")), 1):
        payload = _load_yaml(path)
        rule_id = str(payload.get("rule_id") or path.stem)
        rule_name = str(payload.get("rule_name") or rule_id)
        formula = str(payload.get("formula") or "")
        definition = str(payload.get("definition") or "")
        try:
            company = tools.get_effective_rule(rule_id, None)
            company_formula = str(company.get("formula") or "")
            company_definition = str(company.get("definition") or "")
        except Exception:
            items.append(_item(f"ITEM_{index:03d}", "new_indicator", rule_id, rule_name, "rule", payload, None, "pending"))
            continue
        hospital_minutes = _extract_minutes(formula or definition)
        company_minutes = _extract_minutes(company_formula or company_definition)
        if hospital_minutes and company_minutes and hospital_minutes != company_minutes:
            items.append(_item(f"ITEM_{index:03d}", "caliber_conflict", rule_id, rule_name, "minutes_threshold", f"{hospital_minutes}\u5206\u949f", f"{company_minutes}\u5206\u949f", "pending", payload))
        elif _normalize(formula) != _normalize(company_formula) or _normalize(definition) != _normalize(company_definition):
            items.append(_item(f"ITEM_{index:03d}", "caliber_conflict", rule_id, rule_name, "formula_or_definition", {"definition": definition, "formula": formula}, {"definition": company_definition, "formula": company_formula}, "pending", payload))
        else:
            items.append(_item(f"ITEM_{index:03d}", "unchanged", rule_id, rule_name, "formula_or_definition", None, None, "skipped", payload))
    return items


def _diff_mappings(source_dir: Path, hospital_id: str, existing_items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    mapping_dir = source_dir / "mappings"
    if not mapping_dir.exists():
        return []
    offset = len(existing_items)
    items: list[dict[str, Any]] = []
    for index, path in enumerate(sorted(mapping_dir.glob("*.yaml")), 1):
        payload = _load_yaml(path)
        custom_rules = payload.get("custom_rules") or {}
        exclude_depts = custom_rules.get("exclude_depts") or []
        if exclude_depts:
            rule_id = str(payload.get("rule_id") or path.stem)
            items.append(_item(f"ITEM_{offset + index:03d}", "new_rule", rule_id, rule_id, "exclude_depts", exclude_depts, [], "pending", payload))
    return items


def _diff_new_indicators(source_dir: Path) -> list[dict[str, Any]]:
    base = source_dir / "new-indicators"
    if not base.exists():
        return []
    items = []
    for index, path in enumerate(sorted([p for p in base.iterdir() if p.is_dir()]), 1):
        files = sorted(child.name for child in path.iterdir() if child.is_file())
        items.append({
            "item_id": f"NEW_{index:03d}",
            "type": "new_indicator",
            "indicator_name": path.name,
            "field": "indicator",
            "files": files,
            "status": "pending",
        })
    return items


def _item(item_id: str, item_type: str, rule_id: str, rule_name: str, field: str, hospital_value: Any, company_value: Any, status: str, source_payload: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "item_id": item_id,
        "type": item_type,
        "rule_id": rule_id,
        "rule_name": rule_name,
        "field": field,
        "hospital_value": hospital_value,
        "company_value": company_value,
        "status": status,
        "source_payload": source_payload or {},
    }


def _summarize(items: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "total_items": len(items),
        "conflicts": sum(1 for item in items if item.get("type") == "caliber_conflict"),
        "new_indicators": sum(1 for item in items if item.get("type") == "new_indicator"),
        "new_rules": sum(1 for item in items if item.get("type") == "new_rule"),
        "unchanged": sum(1 for item in items if item.get("type") == "unchanged"),
        "pending": sum(1 for item in items if item.get("status") == "pending"),
    }


def _refresh_report_status(report: dict[str, Any]) -> None:
    report["summary"] = _summarize(report.get("items", []))
    if report["summary"]["pending"] == 0:
        report["status"] = "reviewed"


def _find_item(report: dict[str, Any], item_id: str) -> dict[str, Any]:
    for item in report.get("items", []):
        if item.get("item_id") == item_id:
            return item
    raise MergeError(f"MERGE_ITEM_NOT_FOUND: {item_id}")


def _write_report(report_dir: Path, report: dict[str, Any]) -> None:
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


def _append_audit(report_dir: Path, line: str) -> None:
    with (report_dir / "audit.log").open("a", encoding="utf-8") as f:
        f.write(f"{datetime.now().isoformat(timespec='seconds')} {line}\n")


def _load_yaml(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise MergeError(f"REQUIRED_FILE_MISSING: {path.name}")
    with path.open(encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _extract_minutes(text: str) -> int | None:
    match = re.search(r"(\d+)\s*\u5206\u949f", text or "")
    return int(match.group(1)) if match else None


def _normalize(text: str) -> str:
    return re.sub(r"\s+", "", str(text or "").lower())


def _safe_id(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", str(text)).strip("_") or "item"

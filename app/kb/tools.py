from __future__ import annotations

import json
import re
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_KB_ROOT = PROJECT_ROOT / "core-rules-wiki"


class KBToolError(RuntimeError):
    """Raised when a knowledge-base tool cannot complete safely."""


def _normalize(text: str) -> str:
    return re.sub(r"\s+", "", str(text).lower())


def _safe_filename(text: str) -> str:
    value = re.sub(r"\s+", "", text.strip()) or "change"
    for old, new in {
        "/": "／",
        "\\": "／",
        ":": "：",
        "*": "＊",
        "?": "？",
        '"': "＂",
        "<": "＜",
        ">": "＞",
        "|": "｜",
    }.items():
        value = value.replace(old, new)
    return value[:80]


def _section(markdown: str, title: str) -> str:
    pattern = re.compile(rf"^## {re.escape(title)}\s*\n(?P<body>.*?)(?=^## |\Z)", re.M | re.S)
    match = pattern.search(markdown)
    return match.group("body").strip() if match else ""


def _derive_feedback_value(base: str, feedback: str) -> str:
    base_text = str(base or "")
    feedback_text = str(feedback or "")
    minute_pattern = "(\\d+)\\s*\u5206\u949f"
    match = re.search(minute_pattern, feedback_text)
    if match and re.search(minute_pattern, base_text):
        return re.sub(minute_pattern, f"{match.group(1)}\u5206\u949f", base_text, count=1)
    return feedback_text or base_text


def _parse_front_matter(markdown: str) -> dict[str, str]:
    match = re.match(r"^---\n(?P<body>.*?)\n---", markdown, re.S)
    if not match:
        return {}
    data: dict[str, str] = {}
    for line in match.group("body").splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        data[key.strip()] = value.strip()
    return data


class KnowledgeBaseTools:
    def __init__(self, kb_root: str | Path = DEFAULT_KB_ROOT) -> None:
        self.kb_root = Path(kb_root)

    def _read_text(self, rel_path: str) -> str:
        path = self.kb_root / rel_path
        if not path.exists():
            raise KBToolError(f"KB_PAGE_MISSING: {rel_path}")
        return path.read_text(encoding="utf-8")

    def _read_json(self, rel_path: str) -> Any:
        path = self.kb_root / rel_path
        if not path.exists():
            raise KBToolError(f"KB_INDEX_MISSING: {rel_path}")
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise KBToolError(f"KB_INDEX_INVALID: {rel_path}: {exc}") from exc

    def resolve_rule(self, query: str) -> dict[str, Any] | None:
        rules = self._read_json("indexes/rule_index.json").get("rules", [])
        q = _normalize(query)
        for rule in rules:
            candidates = [rule.get("rule_id", ""), rule.get("rule_name", ""), *rule.get("aliases", [])]
            if any(_normalize(candidate) == q for candidate in candidates):
                return rule
        for rule in rules:
            candidates = [rule.get("rule_id", ""), rule.get("rule_name", ""), *rule.get("aliases", [])]
            if any(q in _normalize(candidate) or _normalize(candidate) in q for candidate in candidates):
                return rule
        return None

    def search(self, query: str, limit: int = 5) -> dict[str, Any]:
        rule = self.resolve_rule(query)
        chunks = self._read_json("indexes/search_index.json")
        q = _normalize(query)
        scored: list[tuple[int, dict[str, Any]]] = []
        for chunk in chunks:
            haystack = _normalize(
                " ".join(
                    [
                        str(chunk.get("rule_id", "")),
                        str(chunk.get("title", "")),
                        str(chunk.get("section", "")),
                        " ".join(chunk.get("keywords", [])),
                        str(chunk.get("content", "")),
                    ]
                )
            )
            score = 0
            if rule and chunk.get("rule_id") == rule.get("rule_id"):
                score += 10
            if q and q in haystack:
                score += 5
            if score:
                scored.append((score, chunk))
        scored.sort(key=lambda item: item[0], reverse=True)
        return {
            "query": query,
            "resolved_rule_id": rule.get("rule_id") if rule else None,
            "matches": [chunk for _, chunk in scored[:limit]],
        }

    def get_hospital_override(self, rule_id: str, hospital_id: str | None) -> dict[str, Any] | None:
        if not hospital_id:
            return None
        index = self._read_json("indexes/hospital_override_index.json")
        for item in index.get("hospital_overrides", []):
            if item.get("rule_id") == rule_id and item.get("hospital_id") == hospital_id and item.get("status") == "approved":
                return item
        return None

    def get_field_mapping(self, rule_id: str) -> dict[str, Any]:
        index = self._read_json("indexes/field_index.json")
        return {
            "rule_id": rule_id,
            "status": index.get("status", "unknown"),
            "items": [item for item in index.get("field_roles", []) if item.get("rule_id") == rule_id],
        }

    def get_relations(self, rule_id: str) -> dict[str, Any]:
        return self._read_json("indexes/relation_index.json").get(rule_id, {})

    def get_effective_rule(self, rule_id_or_name: str, hospital_id: str | None = None) -> dict[str, Any]:
        rule = self.resolve_rule(rule_id_or_name)
        if not rule:
            raise KBToolError(f"RULE_NOT_FOUND: {rule_id_or_name}")

        rule_id = rule["rule_id"]
        warnings: list[str] = []
        national_markdown = self._read_text(rule["national_path"])
        company_markdown = self._read_text(rule["company_path"])
        hospital_item = self.get_hospital_override(rule_id, hospital_id)

        national = {
            "path": rule["national_path"],
            "definition": _section(national_markdown, "指标定义"),
            "formula": _section(national_markdown, "计算公式"),
        }
        company = {
            "path": rule["company_path"],
            "implementation": _section(company_markdown, "公司实现口径"),
            "implementation_status": _section(company_markdown, "公司标准 SQL"),
        }

        effective_level = "company"
        definition = national["definition"]
        formula = national["formula"]
        implementation_status = company["implementation_status"]
        hospital_override = None

        if hospital_id and not hospital_item:
            warnings.append("hospital_override_not_configured")

        if hospital_item:
            hospital_markdown = self._read_text(hospital_item["path"])
            hospital_override = {
                **hospital_item,
                "definition": _section(hospital_markdown, "本院指标定义"),
                "formula": _section(hospital_markdown, "本院计算公式"),
                "implementation_status": _section(hospital_markdown, "本院标准 SQL"),
            }
            effective_level = "hospital"
            definition = hospital_override["definition"] or definition
            formula = hospital_override["formula"] or formula
            implementation_status = hospital_override["implementation_status"] or implementation_status
        elif not company["implementation"] and not company["implementation_status"]:
            effective_level = "national"
            warnings.append("company_rule_not_configured")

        field_mapping = self.get_field_mapping(rule_id)
        field_status = field_mapping.get("status", "unknown")
        sql_status = "unavailable"
        if implementation_status and "待医院字段映射确认" not in implementation_status and "原文未明确" not in implementation_status:
            sql_status = "available"

        return {
            "rule_id": rule_id,
            "rule_name": rule["rule_name"],
            "category": rule.get("category", ""),
            "hospital_id": hospital_id,
            "effective_level": effective_level,
            "definition": definition,
            "formula": formula,
            "implementation_status": implementation_status,
            "field_status": field_status,
            "sql_status": sql_status,
            "hospital_override": hospital_override,
            "company_rule": company,
            "national_rule": national,
            "fallback_chain": ["hospital", "company", "national"],
            "warnings": warnings,
            "relations": self.get_relations(rule_id),
        }

    def build_feedback_preview(self, rule_id: str, hospital_id: str | None, user_feedback: str) -> dict[str, Any]:
        effective = self.get_effective_rule(rule_id, hospital_id)
        hospital_override = effective.get("hospital_override")
        company_rule = effective.get("company_rule") or {}
        national_rule = effective.get("national_rule") or {}
        requested_formula = _derive_feedback_value(effective.get("formula", ""), user_feedback)
        requested_definition = _derive_feedback_value(effective.get("definition", ""), user_feedback)

        current_effective = {
            "level": effective.get("effective_level", ""),
            "status": "effective",
            "definition": effective.get("definition", ""),
            "formula": effective.get("formula", ""),
            "implementation_status": effective.get("implementation_status", ""),
        }
        current_hospital = {
            "level": "hospital",
            "status": "configured" if hospital_override else "not_configured",
            "definition": (hospital_override or {}).get("definition", ""),
            "formula": (hospital_override or {}).get("formula", ""),
            "implementation_status": (hospital_override or {}).get("implementation_status", ""),
        }
        company = {
            "level": "company",
            "status": "configured",
            "definition": company_rule.get("definition", ""),
            "formula": company_rule.get("formula", ""),
            "implementation": company_rule.get("implementation", ""),
            "implementation_status": company_rule.get("implementation_status", ""),
        }
        national = {
            "level": "national",
            "status": "configured",
            "definition": national_rule.get("definition", ""),
            "formula": national_rule.get("formula", ""),
        }
        requested = {
            "level": "hospital",
            "status": "requested",
            "definition": requested_definition,
            "formula": requested_formula,
            "source_text": user_feedback,
        }

        # 字段级变更对比
        _fields = [
            ("指标定义", "definition"),
            ("计算公式", "formula"),
            ("实现状态", "implementation_status"),
        ]
        field_changes: list[dict[str, Any]] = []
        for label, key in _fields:
            req_val = requested.get(key, "") if key != "implementation_status" else ""
            cur_val = current_effective.get(key, "")
            field_changes.append({
                "field": label,
                "requested": str(req_val or ""),
                "current": str(cur_val or ""),
                "changed": bool(req_val and req_val != cur_val),
            })

        return {
            "rule_id": effective["rule_id"],
            "rule_name": effective["rule_name"],
            "hospital_id": hospital_id,
            "target_level": "hospital",
            "current_effective_level": effective["effective_level"],
            # 医院前台弹窗使用
            "requested": requested,
            "current_effective": current_effective,
            "options": [requested, current_effective],
            "field_changes": field_changes,
            # 后台审批参考使用
            "current_hospital": current_hospital,
            "company": company,
            "national": national,
            "references": [current_hospital, company, national],
            "message": "检测到本院口径反馈，请确认差异后再提交变更申请。",
        }

    def submit_change_request(self, payload: dict[str, Any]) -> dict[str, Any]:
        target_level = str(payload.get("target_level") or "hospital")
        if target_level != "hospital":
            raise KBToolError("ONLY_HOSPITAL_LEVEL_CHANGE_SUPPORTED_IN_MVP")
        rule = self.resolve_rule(str(payload.get("rule_id") or payload.get("indicator_name") or ""))
        if not rule:
            raise KBToolError(f"RULE_NOT_FOUND: {payload.get('rule_id', '')}")
        hospital_id = str(payload.get("hospital_id") or "").strip()
        if not hospital_id:
            raise KBToolError("HOSPITAL_ID_REQUIRED")

        now = datetime.now().strftime("%Y%m%d_%H%M%S")
        created_at = datetime.now().isoformat(timespec="seconds")
        change_id = f"CR_{now}_{uuid.uuid4().hex[:6]}"
        title = _safe_filename(str(payload.get("indicator_name") or rule.get("rule_name") or rule.get("rule_id") or "本院口径反馈"))
        rel_path = f"review/pending/{change_id}_{title}.md"
        path = self.kb_root / rel_path
        path.parent.mkdir(parents=True, exist_ok=True)
        requested_definition = str(payload.get("requested_definition") or "")
        requested_formula = str(payload.get("requested_formula") or payload.get("hospital_feedback") or "")
        submitter_id = str(payload.get("submitter_id") or "unknown")
        submitter_role = str(payload.get("submitter_role") or "unknown")

        content = f"""---
type: change_request
change_id: {change_id}
status: pending
rule_id: {rule.get('rule_id', '')}
indicator_name: {rule.get('rule_name', '')}
hospital_id: {hospital_id}
target_level: hospital
submitter_id: {submitter_id}
submitter_role: {submitter_role}
created_at: {created_at}
---
# {change_id}_{title}

## 指标

- rule_id: {rule.get('rule_id', '')}
- indicator_name: {rule.get('rule_name', '')}
- hospital_id: {hospital_id}
- target_level: hospital
- change_type: {payload.get('change_type', '本院口径反馈')}
- status: pending
- 提交人: {submitter_id}（{submitter_role}）
- 提交时间: {created_at}

## 请求定义

{requested_definition}

## 请求公式

{requested_formula}

## 用户反馈

{payload.get('hospital_feedback', '用户未明确')}

## 原始用户消息

{payload.get('original_user_message', '')}

## 审核要求

该变更仅进入 pending，不直接写入正式 wiki。人工审核通过后只生成医院 override，不修改公司标准或国标。
"""
        path.write_text(content, encoding="utf-8")
        return {
            "change_id": change_id,
            "status": "pending",
            "path": rel_path,
            "target_level": "hospital",
            "rule_id": rule.get("rule_id", ""),
            "hospital_id": hospital_id,
            "submitter_id": submitter_id,
            "submitter_role": submitter_role,
            "created_at": created_at,
        }

    def _pending_path_for(self, change_id: str) -> Path:
        matches = sorted((self.kb_root / "review" / "pending").glob(f"{change_id}_*.md"))
        if not matches:
            raise KBToolError(f"CHANGE_REQUEST_NOT_FOUND: {change_id}")
        return matches[0]

    def _change_request_from_path(self, path: Path) -> dict[str, Any]:
        markdown = path.read_text(encoding="utf-8")
        meta = _parse_front_matter(markdown)
        rel_path = path.relative_to(self.kb_root).as_posix()
        return {
            **meta,
            "path": rel_path,
            "requested_definition": _section(markdown, "请求定义"),
            "requested_formula": _section(markdown, "请求公式"),
            "hospital_feedback": _section(markdown, "用户反馈"),
            "original_user_message": _section(markdown, "原始用户消息"),
            "submitter_id": meta.get("submitter_id", "unknown"),
            "submitter_role": meta.get("submitter_role", "unknown"),
            "created_at": meta.get("created_at", ""),
        }

    def list_pending_change_requests(self) -> list[dict[str, Any]]:
        pending_dir = self.kb_root / "review" / "pending"
        if not pending_dir.exists():
            return []
        items = [self._change_request_from_path(path) for path in sorted(pending_dir.glob("CR_*.md"))]
        return [item for item in items if item.get("status") == "pending"]

    def approve_change_request(self, change_id: str, approver_id: str = "admin") -> dict[str, Any]:
        pending_path = self._pending_path_for(change_id)
        request = self._change_request_from_path(pending_path)
        if request.get("target_level") != "hospital":
            raise KBToolError("ONLY_HOSPITAL_LEVEL_CHANGE_SUPPORTED_IN_MVP")
        rule = self.resolve_rule(str(request.get("rule_id") or ""))
        if not rule:
            raise KBToolError(f"RULE_NOT_FOUND: {request.get('rule_id', '')}")
        hospital_id = str(request.get("hospital_id") or "").strip()
        if not hospital_id:
            raise KBToolError("HOSPITAL_ID_REQUIRED")

        approved_at = datetime.now().isoformat(timespec="seconds")
        override_rel_path = f"wiki/hospitals/{hospital_id}/overrides/{rule['rule_id']}_override.md"
        override_path = self.kb_root / override_rel_path
        override_path.parent.mkdir(parents=True, exist_ok=True)
        requested_definition = request.get("requested_definition") or ""
        requested_formula = request.get("requested_formula") or ""
        override_path.write_text(
            f"""# {rule['rule_name']}_本院口径

## 本院指标定义

{requested_definition}

## 本院计算公式

{requested_formula}

## 本院标准 SQL

待医院字段映射确认。

## 审批信息

- change_id: {change_id}
- approved_at: {approved_at}
- approver_id: {approver_id}
- target_level: hospital
""",
            encoding="utf-8",
        )

        index_path = self.kb_root / "indexes" / "hospital_override_index.json"
        index = self._read_json("indexes/hospital_override_index.json")
        overrides = index.setdefault("hospital_overrides", [])
        replacement = {
            "hospital_id": hospital_id,
            "rule_id": rule["rule_id"],
            "path": override_rel_path,
            "status": "approved",
            "version": f"{hospital_id}_{approved_at}",
            "change_id": change_id,
        }
        updated = False
        for idx, item in enumerate(overrides):
            if item.get("hospital_id") == hospital_id and item.get("rule_id") == rule["rule_id"]:
                overrides[idx] = replacement
                updated = True
                break
        if not updated:
            overrides.append(replacement)
        index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")

        approved_dir = self.kb_root / "review" / "approved"
        approved_dir.mkdir(parents=True, exist_ok=True)
        approved_path = approved_dir / pending_path.name
        approved_text = pending_path.read_text(encoding="utf-8").replace("status: pending", "status: approved")
        approved_text = approved_text + f"\n## 审批结果\n\n- approved_at: {approved_at}\n- approver_id: {approver_id}\n"
        approved_path.write_text(approved_text, encoding="utf-8")
        pending_path.write_text(approved_text, encoding="utf-8")
        return {
            "change_id": change_id,
            "status": "approved",
            "target_level": "hospital",
            "override_path": override_rel_path,
            "approved_path": approved_path.relative_to(self.kb_root).as_posix(),
            "approved_at": approved_at,
            "approver_id": approver_id,
        }

    def reject_change_request(self, change_id: str, approver_id: str = "admin") -> dict[str, Any]:
        pending_path = self._pending_path_for(change_id)
        rejected_at = datetime.now().isoformat(timespec="seconds")
        rejected_dir = self.kb_root / "review" / "rejected"
        rejected_dir.mkdir(parents=True, exist_ok=True)
        rejected_path = rejected_dir / pending_path.name
        rejected_text = pending_path.read_text(encoding="utf-8").replace("status: pending", "status: rejected")
        rejected_text = rejected_text + f"\n## 审批结果\n\n- rejected_at: {rejected_at}\n- approver_id: {approver_id}\n"
        rejected_path.write_text(rejected_text, encoding="utf-8")
        pending_path.write_text(rejected_text, encoding="utf-8")
        return {"change_id": change_id, "status": "rejected", "path": rejected_path.relative_to(self.kb_root).as_posix(), "rejected_at": rejected_at, "approver_id": approver_id}

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


def _tokenize(text: str) -> set[str]:
    """将中文文本切分为 unigram+bigram token 集合，用于模糊匹配。"""
    t = _normalize(text)
    tokens: set[str] = set()
    for i, ch in enumerate(t):
        tokens.add(ch)
        if i + 1 < len(t):
            tokens.add(t[i:i + 2])
    return tokens


def _token_overlap(query: str, candidate: str) -> float:
    """返回 query 与 candidate 的 token Jaccard 相似度 (0~1)。"""
    q_tokens = _tokenize(query)
    c_tokens = _tokenize(candidate)
    if not q_tokens or not c_tokens:
        return 0.0
    intersection = q_tokens & c_tokens
    union = q_tokens | c_tokens
    return len(intersection) / len(union) if union else 0.0


def _safe_id(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", str(text)).strip("_") or "item"


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


def atomic_write_text(path: Path, content: str, encoding: str = "utf-8") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f"{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        tmp_path.write_text(content, encoding=encoding)
        tmp_path.replace(path)
    except Exception:
        if tmp_path.exists():
            tmp_path.unlink()
        raise


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
        # Phase 1: exact match
        for rule in rules:
            candidates = [rule.get("rule_id", ""), rule.get("rule_name", ""), *rule.get("aliases", [])]
            if any(_normalize(candidate) == q for candidate in candidates):
                return rule
        # Phase 2: substring match
        for rule in rules:
            candidates = [rule.get("rule_id", ""), rule.get("rule_name", ""), *rule.get("aliases", [])]
            if any(q in _normalize(candidate) or _normalize(candidate) in q for candidate in candidates):
                return rule
        # Phase 3: token-based fuzzy match（用户措辞不精确时的最后兜底）
        best_score = 0.0
        best_rule = None
        for rule in rules:
            for candidate in [rule.get("rule_name", ""), *rule.get("aliases", [])]:
                score = _token_overlap(query, candidate)
                if score > best_score:
                    best_score = score
                    best_rule = rule
        if best_score >= 0.35 and best_rule:
            return best_rule
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
            # rule_id 精确命中
            if rule and chunk.get("rule_id") == rule.get("rule_id"):
                score += 10
            # 子串匹配
            if q and q in haystack:
                score += 5
            # token 重叠加分（用户措辞不精确时依然有分）
            token_score = int(_token_overlap(query, haystack) * 8)
            if token_score > 0:
                score += token_score
            if score:
                scored.append((score, chunk))
        scored.sort(key=lambda item: item[0], reverse=True)
        return {
            "query": query,
            "resolved_rule_id": rule.get("rule_id") if rule else None,
            "matches": [chunk for _, chunk in scored[:limit]],
        }

    def search_terms(self, query: str, limit: int = 10) -> dict[str, Any]:
        """只读检索术语解释；Wiki 兜底不返回医院编码或数据库值。"""
        terms = self._read_json("indexes/term_index.json")
        normalized_query = _normalize(query)
        matches: list[tuple[int, dict[str, Any]]] = []
        for term in terms:
            candidates = [
                str(term.get("canonical_name") or ""),
                *[
                    str(alias.get("alias_text") or "")
                    for alias in term.get("aliases", [])
                    if alias.get("retrieval_enabled", True)
                ],
            ]
            score = 0
            if any(_normalize(candidate) == normalized_query for candidate in candidates):
                score = 100
            elif any(
                normalized_query in _normalize(candidate)
                or _normalize(candidate) in normalized_query
                for candidate in candidates
                if candidate
            ):
                score = 50
            else:
                score = max(
                    [int(_token_overlap(query, candidate) * 20) for candidate in candidates]
                    or [0]
                )
            if score:
                safe = {
                    key: value
                    for key, value in term.items()
                    if key not in {"local_code", "local_value", "hospital_id"}
                }
                matches.append((score, safe))
        matches.sort(key=lambda item: (-item[0], str(item[1].get("concept_code") or "")))
        return {"query": query, "matches": [item for _, item in matches[:limit]]}

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
        # 检查新版 hospital-mappings 是否已配置（覆盖旧的 markdown 状态）
        if sql_status == "unavailable" and hospital_id:
            mapping_path = self.kb_root / "hospital-mappings" / hospital_id / f"{rule_id}.yaml"
            if mapping_path.exists():
                import yaml
                with open(mapping_path, encoding="utf-8") as f:
                    mapping = yaml.safe_load(f) or {}
                if mapping.get("status") == "confirmed" and mapping.get("fields"):
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
        atomic_write_text(path, content)
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

    def _write_json(self, rel_path: str, data: object) -> None:
        path = self.kb_root / rel_path
        path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_text(path, json.dumps(data, ensure_ascii=False, indent=2))

    def _active_override_rel_path(self, hospital_id: str, rule_id: str) -> str:
        return f"wiki/hospitals/{hospital_id}/overrides/{rule_id}_override.md"

    def _version_override_rel_path(self, hospital_id: str, rule_id: str, version_id: str) -> str:
        return f"wiki/hospitals/{hospital_id}/overrides/{rule_id}/versions/{_safe_id(version_id)}.md"

    def _override_markdown(
        self,
        rule_name: str,
        definition: str,
        formula: str,
        change_id: str,
        approved_at: str,
        approver_id: str,
        version_id: str,
    ) -> str:
        return f"""# {rule_name}_\u672c\u9662\u53e3\u5f84

## \u672c\u9662\u6307\u6807\u5b9a\u4e49

{definition}

## \u672c\u9662\u8ba1\u7b97\u516c\u5f0f

{formula}

## \u672c\u9662\u6807\u51c6 SQL

\u5f85\u533b\u9662\u5b57\u6bb5\u6620\u5c04\u786e\u8ba4\u3002

## \u5ba1\u6279\u4fe1\u606f

- change_id: {change_id}
- version_id: {version_id}
- approved_at: {approved_at}
- approver_id: {approver_id}
- target_level: hospital
"""

    def _find_override_entry(self, index: dict[str, Any], rule_id: str, hospital_id: str) -> dict[str, Any] | None:
        for item in index.setdefault("hospital_overrides", []):
            if item.get("rule_id") == rule_id and item.get("hospital_id") == hospital_id:
                return item
        return None

    def _version_summary(self, version: dict[str, Any]) -> dict[str, Any]:
        result = dict(version)
        rel_path = str(version.get("path") or "")
        if rel_path and (self.kb_root / rel_path).exists():
            markdown = self._read_text(rel_path)
            result["definition"] = _section(markdown, "\u672c\u9662\u6307\u6807\u5b9a\u4e49")
            result["formula"] = _section(markdown, "\u672c\u9662\u8ba1\u7b97\u516c\u5f0f")
            result["implementation_status"] = _section(markdown, "\u672c\u9662\u6807\u51c6 SQL")
        return result

    def _normalize_override_entry(self, item: dict[str, Any], rule: dict[str, Any]) -> dict[str, Any]:
        hospital_id = str(item.get("hospital_id") or "")
        rule_id = str(item.get("rule_id") or rule.get("rule_id") or "")
        active_rel_path = str(item.get("path") or self._active_override_rel_path(hospital_id, rule_id))
        item["hospital_id"] = hospital_id
        item["rule_id"] = rule_id
        item["path"] = active_rel_path
        item["status"] = item.get("status") or "approved"
        versions = item.setdefault("versions", [])
        active_version_id = str(item.get("active_version_id") or item.get("version") or "")
        active_version_path = str(item.get("active_version_path") or "")

        if not versions and active_rel_path and (self.kb_root / active_rel_path).exists():
            legacy_id = active_version_id or f"legacy_{uuid.uuid4().hex[:8]}"
            legacy_rel_path = self._version_override_rel_path(hospital_id, rule_id, legacy_id)
            legacy_path = self.kb_root / legacy_rel_path
            legacy_path.parent.mkdir(parents=True, exist_ok=True)
            if not legacy_path.exists():
                atomic_write_text(legacy_path, (self.kb_root / active_rel_path).read_text(encoding="utf-8"))
            versions.append(
                {
                    "version_id": legacy_id,
                    "path": legacy_rel_path,
                    "change_id": item.get("change_id", "legacy_import"),
                    "approved_at": item.get("approved_at", ""),
                    "approver_id": item.get("approver_id", "legacy"),
                    "source": "legacy_import",
                    "status": "approved",
                }
            )
            active_version_id = legacy_id
            active_version_path = legacy_rel_path

        if versions and not active_version_id:
            active_version_id = str(versions[-1].get("version_id") or "")
            active_version_path = str(versions[-1].get("path") or "")
        if versions and not active_version_path:
            for version in versions:
                if version.get("version_id") == active_version_id:
                    active_version_path = str(version.get("path") or "")
                    break

        item["active_version_id"] = active_version_id
        item["active_version_path"] = active_version_path
        item["version"] = active_version_id
        if active_version_path and not (self.kb_root / active_rel_path).exists() and (self.kb_root / active_version_path).exists():
            active_path = self.kb_root / active_rel_path
            active_path.parent.mkdir(parents=True, exist_ok=True)
            atomic_write_text(active_path, (self.kb_root / active_version_path).read_text(encoding="utf-8"))
        return item

    def list_hospital_override_versions(self, rule_id_or_name: str, hospital_id: str) -> dict[str, Any]:
        rule = self.resolve_rule(rule_id_or_name)
        if not rule:
            raise KBToolError(f"RULE_NOT_FOUND: {rule_id_or_name}")
        index = self._read_json("indexes/hospital_override_index.json")
        item = self._find_override_entry(index, rule["rule_id"], hospital_id)
        if not item:
            return {"rule_id": rule["rule_id"], "rule_name": rule["rule_name"], "hospital_id": hospital_id, "active_version_id": None, "versions": []}
        self._normalize_override_entry(item, rule)
        self._write_json("indexes/hospital_override_index.json", index)
        return {
            "rule_id": rule["rule_id"],
            "rule_name": rule["rule_name"],
            "hospital_id": hospital_id,
            "active_version_id": item.get("active_version_id"),
            "active_version_path": item.get("active_version_path"),
            "active_path": item.get("path"),
            "versions": [self._version_summary(version) for version in item.get("versions", [])],
            "restore_events": item.get("restore_events", []),
        }

    def restore_hospital_override_version(self, rule_id_or_name: str, hospital_id: str, version_id: str, approver_id: str = "admin") -> dict[str, Any]:
        rule = self.resolve_rule(rule_id_or_name)
        if not rule:
            raise KBToolError(f"RULE_NOT_FOUND: {rule_id_or_name}")
        index = self._read_json("indexes/hospital_override_index.json")
        item = self._find_override_entry(index, rule["rule_id"], hospital_id)
        if not item:
            raise KBToolError(f"HOSPITAL_OVERRIDE_NOT_FOUND: {hospital_id}/{rule['rule_id']}")
        self._normalize_override_entry(item, rule)
        target = None
        for version in item.get("versions", []):
            if version.get("version_id") == version_id:
                target = version
                break
        if not target:
            raise KBToolError(f"HOSPITAL_OVERRIDE_VERSION_NOT_FOUND: {version_id}")
        version_path = self.kb_root / str(target.get("path") or "")
        if not version_path.exists():
            raise KBToolError(f"HOSPITAL_OVERRIDE_VERSION_FILE_MISSING: {target.get('path')}")
        active_rel_path = self._active_override_rel_path(hospital_id, rule["rule_id"])
        active_path = self.kb_root / active_rel_path
        active_path.parent.mkdir(parents=True, exist_ok=True)
        restored_content = version_path.read_text(encoding="utf-8")
        atomic_write_text(active_path, restored_content)
        restored_at = datetime.now().isoformat(timespec="seconds")
        restored_version_id = (
            f"{hospital_id}_{rule['rule_id']}_restore_"
            f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
        )
        restored_version_rel_path = self._version_override_rel_path(
            hospital_id, rule["rule_id"], restored_version_id
        )
        atomic_write_text(
            self.kb_root / restored_version_rel_path, restored_content
        )
        item.setdefault("versions", []).append(
            {
                "version_id": restored_version_id,
                "path": restored_version_rel_path,
                "change_id": f"RESTORE_{uuid.uuid4().hex[:12]}",
                "approved_at": restored_at,
                "approver_id": approver_id,
                "source": "restore",
                "restored_from_version": version_id,
                "status": "approved",
            }
        )
        item["path"] = active_rel_path
        item["active_version_id"] = restored_version_id
        item["active_version_path"] = restored_version_rel_path
        item["version"] = restored_version_id
        item["status"] = "approved"
        item.setdefault("restore_events", []).append({"version_id": version_id, "restored_at": restored_at, "approver_id": approver_id})
        self._write_json("indexes/hospital_override_index.json", index)
        self.rebuild_runtime_indexes()
        return {
            "status": "restored",
            "rule_id": rule["rule_id"],
            "hospital_id": hospital_id,
            "active_version_id": restored_version_id,
            "active_version_path": item.get("active_version_path"),
            "override_path": active_rel_path,
            "restored_from_version": version_id,
            "restored_at": restored_at,
            "approver_id": approver_id,
        }

    def rebuild_runtime_indexes(self) -> dict[str, Any]:
        rule_index = self._read_json("indexes/rule_index.json")
        relation_index = self._read_json("indexes/relation_index.json")
        search_index = self._read_json("indexes/search_index.json")
        override_index = self._read_json("indexes/hospital_override_index.json")
        rules_by_id = {str(rule.get("rule_id")): rule for rule in rule_index.get("rules", [])}

        for rel in relation_index.values():
            rel.setdefault("relations", {})["has_hospital_override"] = []
        search_index = [chunk for chunk in search_index if chunk.get("type") != "hospital_override"]

        active_count = 0
        for item in override_index.setdefault("hospital_overrides", []):
            rule_id = str(item.get("rule_id") or "")
            rule = rules_by_id.get(rule_id)
            if not rule or item.get("status") != "approved":
                continue
            self._normalize_override_entry(item, rule)
            active_path = str(item.get("path") or "")
            if not active_path or not (self.kb_root / active_path).exists():
                continue
            markdown = self._read_text(active_path)
            definition = _section(markdown, "\u672c\u9662\u6307\u6807\u5b9a\u4e49")
            formula = _section(markdown, "\u672c\u9662\u8ba1\u7b97\u516c\u5f0f")
            implementation = _section(markdown, "\u672c\u9662\u6807\u51c6 SQL")
            relation_index.setdefault(rule_id, {"rule_name": rule.get("rule_name", ""), "relations": {}}).setdefault("relations", {})["has_hospital_override"].append(
                {
                    "hospital_id": item.get("hospital_id"),
                    "target_path": active_path,
                    "active_version_id": item.get("active_version_id"),
                    "active_version_path": item.get("active_version_path"),
                    "status": "approved",
                }
            )
            sections = [
                ("\u672c\u9662\u6307\u6807\u5b9a\u4e49", definition),
                ("\u672c\u9662\u8ba1\u7b97\u516c\u5f0f", formula),
                ("\u672c\u9662\u6807\u51c6 SQL", implementation),
            ]
            for section, content in sections:
                search_index.append(
                    {
                        "chunk_id": f"{rule_id}_hospital_{_safe_id(str(item.get('hospital_id')))}_{_safe_id(section)}",
                        "rule_id": rule_id,
                        "title": f"{rule.get('rule_name', rule_id)}_{section}",
                        "path": active_path,
                        "type": "hospital_override",
                        "level": "hospital",
                        "hospital_id": item.get("hospital_id"),
                        "section": section,
                        "keywords": [rule_id, str(rule.get("rule_name", "")), str(item.get("hospital_id", "")), section],
                        "related_rule_ids": [],
                        "related_fields": [],
                        "related_tables": [],
                        "active_version_id": item.get("active_version_id"),
                        "content": content,
                    }
                )
            active_count += 1

        self._write_json("indexes/hospital_override_index.json", override_index)
        self._write_json("indexes/relation_index.json", relation_index)
        self._write_json("indexes/search_index.json", search_index)
        return {"hospital_overrides": active_count, "search_chunks": len(search_index), "relation_rules": len(relation_index)}

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
        version_id = f"{hospital_id}_{rule['rule_id']}_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
        active_rel_path = self._active_override_rel_path(hospital_id, rule["rule_id"])
        version_rel_path = self._version_override_rel_path(hospital_id, rule["rule_id"], version_id)
        requested_definition = request.get("requested_definition") or ""
        requested_formula = request.get("requested_formula") or ""
        content = self._override_markdown(rule["rule_name"], requested_definition, requested_formula, change_id, approved_at, approver_id, version_id)

        version_path = self.kb_root / version_rel_path
        version_path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_text(version_path, content)
        active_path = self.kb_root / active_rel_path
        active_path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_text(active_path, content)

        index = self._read_json("indexes/hospital_override_index.json")
        overrides = index.setdefault("hospital_overrides", [])
        item = self._find_override_entry(index, rule["rule_id"], hospital_id)
        if item is None:
            item = {"hospital_id": hospital_id, "rule_id": rule["rule_id"], "status": "approved", "path": active_rel_path, "versions": []}
            overrides.append(item)
        else:
            self._normalize_override_entry(item, rule)
        item["path"] = active_rel_path
        item["status"] = "approved"
        item["version"] = version_id
        item["active_version_id"] = version_id
        item["active_version_path"] = version_rel_path
        item["change_id"] = change_id
        item.setdefault("versions", []).append(
            {
                "version_id": version_id,
                "path": version_rel_path,
                "change_id": change_id,
                "approved_at": approved_at,
                "approver_id": approver_id,
                "source": "approval",
                "status": "approved",
            }
        )
        self._write_json("indexes/hospital_override_index.json", index)
        self.rebuild_runtime_indexes()

        approved_dir = self.kb_root / "review" / "approved"
        approved_dir.mkdir(parents=True, exist_ok=True)
        approved_path = approved_dir / pending_path.name
        approved_text = pending_path.read_text(encoding="utf-8").replace("status: pending", "status: approved")
        approved_text = approved_text + f"\n## \u5ba1\u6279\u7ed3\u679c\n\n- approved_at: {approved_at}\n- approver_id: {approver_id}\n- active_version_id: {version_id}\n"
        atomic_write_text(approved_path, approved_text)
        atomic_write_text(pending_path, approved_text)
        return {
            "change_id": change_id,
            "status": "approved",
            "target_level": "hospital",
            "override_path": active_rel_path,
            "active_version_id": version_id,
            "active_version_path": version_rel_path,
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
        atomic_write_text(rejected_path, rejected_text)
        atomic_write_text(pending_path, rejected_text)
        return {"change_id": change_id, "status": "rejected", "path": rejected_path.relative_to(self.kb_root).as_posix(), "rejected_at": rejected_at, "approver_id": approver_id}

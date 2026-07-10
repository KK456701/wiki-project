from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any

from sqlalchemy import Engine, text

from app.rules.importer import build_indicator_seeds


def import_company_standard_rules(
    engine: Engine, kb_root: Path
) -> dict[str, list[Any]]:
    """只补齐缺失的公司标准初始版本，不覆盖已发布规则。"""

    result: dict[str, list[Any]] = {"inserted": [], "skipped": [], "failed": []}
    for seed in build_indicator_seeds(Path(kb_root)):
        rule_id = str(seed["index_code"])
        try:
            with engine.begin() as conn:
                exists = conn.execute(
                    text(
                        "SELECT 1 FROM company_standard_rule "
                        "WHERE rule_id=:rule_id LIMIT 1"
                    ),
                    {"rule_id": rule_id},
                ).first()
                if exists is not None:
                    result["skipped"].append(rule_id)
                    continue
                now = datetime.now().isoformat(sep=" ", timespec="seconds")
                payload = _seed_payload(seed)
                serialized = json.dumps(payload, ensure_ascii=False)
                conn.execute(
                    text(
                        """
                        INSERT INTO company_standard_rule
                          (rule_id, rule_name, definition, formula, payload_json,
                           version, status, updated_at)
                        VALUES
                          (:rule_id, :rule_name, :definition, :formula,
                           :payload_json, 1, 'published', :updated_at)
                        """
                    ),
                    {
                        "rule_id": rule_id,
                        "rule_name": payload["rule_name"],
                        "definition": payload["definition"],
                        "formula": payload["formula"],
                        "payload_json": serialized,
                        "updated_at": now,
                    },
                )
                conn.execute(
                    text(
                        """
                        INSERT INTO company_standard_rule_version
                          (rule_id, version, payload_json, source_release_id,
                           created_at)
                        VALUES
                          (:rule_id, 1, :payload_json, NULL, :created_at)
                        """
                    ),
                    {
                        "rule_id": rule_id,
                        "payload_json": serialized,
                        "created_at": now,
                    },
                )
            result["inserted"].append(rule_id)
        except Exception as exc:
            result["failed"].append({"rule_id": rule_id, "error": str(exc)})
    return result


def _seed_payload(seed: dict[str, Any]) -> dict[str, Any]:
    name = str(seed["index_name"])
    numerator = str(seed.get("numerator_rule") or "")
    denominator = str(seed.get("denominator_rule") or "")
    return {
        "rule_id": str(seed["index_code"]),
        "rule_name": name,
        "definition": str(seed.get("index_desc") or ""),
        "formula": f"{name} = ({numerator} / {denominator}) × 100%",
        "numerator_rule": numerator,
        "denominator_rule": denominator,
        "filter_rule": str(seed.get("filter_rule") or ""),
        "exclude_rule": str(seed.get("exclude_rule") or ""),
        "rule_params": seed.get("rule_params") or {},
        "standard_sql": str(seed.get("standard_sql") or ""),
        "source_path": str(seed.get("source_path") or ""),
        "base_standard_version": "2025",
        "version": 1,
    }

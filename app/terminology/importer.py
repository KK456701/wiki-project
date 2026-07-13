"""审核术语语料的加载、覆盖校验和导入。"""

from __future__ import annotations

from pathlib import Path
from datetime import datetime
import json
from typing import Any

import yaml
from sqlalchemy import Engine, text

from app.terminology.contracts import CoverageReport, TermCorpus


EXPECTED_SECTIONS = {
    "rule_name", "definition", "numerator", "denominator", "filter", "exclude"
}
EXPECTED_RULES = {f"MQSI2025_{number:03d}" for number in range(1, 36)}


def load_term_corpus(path: str | Path) -> TermCorpus:
    payload = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    return TermCorpus.model_validate(payload)


def validate_term_corpus(corpus: TermCorpus) -> CoverageReport:
    errors: list[str] = []
    rules = set(corpus.rule_coverage)
    if rules != EXPECTED_RULES:
        errors.append(
            f"指标覆盖不完整，缺少 {sorted(EXPECTED_RULES - rules)}，多出 {sorted(rules - EXPECTED_RULES)}"
        )
    concept_codes = [item.concept_code for item in corpus.concepts]
    if len(concept_codes) != len(set(concept_codes)):
        errors.append("concept_code 存在重复")
    known = set(concept_codes)
    pending_count = 0
    for rule_id, coverage in corpus.rule_coverage.items():
        sections = set(coverage.covered_sections) | set(coverage.not_applicable_sections)
        if sections != EXPECTED_SECTIONS:
            errors.append(f"{rule_id} 的文档段落覆盖不完整")
        if coverage.review_status != "approved":
            pending_count += 1
        for link in coverage.concept_links:
            if link.concept_code not in known:
                errors.append(f"{rule_id} 引用了不存在的概念 {link.concept_code}")
            if link.index_code != rule_id:
                errors.append(f"{rule_id} 的关联 index_code 不一致")
    for concept in corpus.concepts:
        for alias in concept.aliases:
            if alias.approval_status != "approved":
                pending_count += 1
            if alias.sql_safe and alias.relation_type in {"related", "forbidden"}:
                errors.append(f"{concept.concept_code}/{alias.alias_text} 的 SQL 安全标记非法")
    return CoverageReport(
        ok=not errors and pending_count == 0,
        rule_count=len(rules),
        concept_count=len(corpus.concepts),
        alias_count=sum(len(item.aliases) for item in corpus.concepts),
        pending_count=pending_count,
        errors=errors,
    )


def import_term_corpus(
    engine: Engine, corpus: TermCorpus, actor_id: str = "terminology_import"
) -> dict[str, int]:
    report = validate_term_corpus(corpus)
    if not report.ok:
        raise ValueError("TERM_CORPUS_INVALID: " + "; ".join(report.errors))
    now = datetime.now()
    changed = 0
    with engine.begin() as conn:
        for concept in corpus.concepts:
            payload = concept.model_dump(exclude={"aliases"})
            existing = conn.execute(
                text("SELECT * FROM med_term_concept WHERE concept_code=:concept_code"),
                {"concept_code": concept.concept_code},
            ).mappings().first()
            values = {
                **payload,
                "created_at": existing.get("created_at") if existing else now,
                "updated_at": now,
            }
            if existing is None:
                conn.execute(
                    text(
                        """
                        INSERT INTO med_term_concept
                          (concept_code, canonical_name, concept_type, definition,
                           standard_code, source_level, source_reference, version,
                           status, created_at, updated_at)
                        VALUES
                          (:concept_code, :canonical_name, :concept_type, :definition,
                           :standard_code, :source_level, :source_reference, :version,
                           :status, :created_at, :updated_at)
                        """
                    ),
                    values,
                )
                changed += 1
            elif _different(existing, values, payload):
                conn.execute(
                    text(
                        """
                        UPDATE med_term_concept SET canonical_name=:canonical_name,
                          concept_type=:concept_type, definition=:definition,
                          standard_code=:standard_code, source_level=:source_level,
                          source_reference=:source_reference, version=:version,
                          status=:status, updated_at=:updated_at
                        WHERE concept_code=:concept_code
                        """
                    ),
                    values,
                )
                changed += 1
            for alias in concept.aliases:
                existing_alias = conn.execute(
                    text(
                        """SELECT * FROM med_term_alias
                           WHERE hospital_id='' AND concept_code=:concept_code
                             AND alias_text=:alias_text
                           ORDER BY version DESC LIMIT 1"""
                    ),
                    {
                        "concept_code": concept.concept_code,
                        "alias_text": alias.alias_text,
                    },
                ).mappings().first()
                alias_values = {
                    **alias.model_dump(),
                    "retrieval_enabled": int(alias.retrieval_enabled),
                    "sql_safe": int(alias.sql_safe),
                    "created_by": actor_id,
                    "approved_by": actor_id,
                    "created_at": now,
                    "approved_at": now,
                }
                comparable = alias.model_dump().keys()
                if existing_alias is None:
                    conn.execute(
                        text(
                            """
                            INSERT INTO med_term_alias
                              (hospital_id, concept_code, alias_text, relation_type, retrieval_enabled,
                               sql_safe, ambiguity_group, source_reference, approval_status,
                               version, created_by, approved_by, created_at, approved_at)
                            VALUES
                              (:hospital_id, :concept_code, :alias_text, :relation_type, :retrieval_enabled,
                               :sql_safe, :ambiguity_group, :source_reference, :approval_status,
                               :version, :created_by, :approved_by, :created_at, :approved_at)
                            """
                        ),
                        alias_values,
                    )
                    changed += 1
                elif _different(existing_alias, alias_values, comparable):
                    conn.execute(
                        text(
                            """
                            UPDATE med_term_alias SET relation_type=:relation_type,
                              retrieval_enabled=:retrieval_enabled, sql_safe=:sql_safe,
                              ambiguity_group=:ambiguity_group,
                              source_reference=:source_reference,
                              approval_status=:approval_status,
                              approved_by=:approved_by, approved_at=:approved_at
                            WHERE id=:id
                            """
                        ),
                        {**alias_values, "id": existing_alias["id"]},
                    )
                    changed += 1
        for rule_id, coverage in corpus.rule_coverage.items():
            for link in coverage.concept_links:
                existing_link = conn.execute(
                    text(
                        """SELECT id FROM med_term_rule_link
                           WHERE concept_code=:concept_code AND index_code=:index_code
                             AND usage_section=:usage_section AND version=:version"""
                    ),
                    link.model_dump(),
                ).first()
                if existing_link is None:
                    conn.execute(
                        text(
                            """
                            INSERT INTO med_term_rule_link
                              (concept_code, index_code, usage_section, business_field_key,
                               source_reference, version)
                            VALUES (:concept_code, :index_code, :usage_section,
                                    :business_field_key, :source_reference, :version)
                            """
                        ),
                        link.model_dump(),
                    )
                    changed += 1
        if changed:
            conn.execute(
                text(
                    """
                    INSERT INTO med_term_audit_log
                      (action, object_type, object_id, hospital_id, version,
                       actor_id, detail_json, created_at)
                    VALUES ('import', 'term_corpus', 'core_indicator_terms', NULL,
                            :version, :actor_id, :detail_json, :created_at)
                    """
                ),
                {
                    "version": corpus.schema_version,
                    "actor_id": actor_id,
                    "detail_json": json.dumps(
                        {"changed_count": changed, "rule_count": len(corpus.rule_coverage)},
                        ensure_ascii=False,
                    ),
                    "created_at": now,
                },
            )
    return {
        "concept_count": report.concept_count,
        "alias_count": report.alias_count,
        "rule_count": report.rule_count,
        "changed_count": changed,
    }


def _different(existing: Any, values: dict[str, Any], keys: Any) -> bool:
    for key in keys:
        old = existing.get(key)
        new = values.get(key)
        if isinstance(new, bool):
            new = int(new)
        if old != new:
            return True
    return False

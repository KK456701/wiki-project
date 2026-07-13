"""确定性医学术语标准化，不调用 LLM 或外部服务。"""

from __future__ import annotations

import time
from collections import defaultdict
from threading import RLock
from typing import Any

from app.terminology.contracts import (
    TermMatch,
    TermNormalizationResult,
)
from app.terminology.repository import TerminologyRepository


class TerminologyNormalizer:
    _corpus_cache: dict[tuple[str, str], list[dict[str, Any]]] = {}
    _cache_lock = RLock()

    def __init__(self, repository: TerminologyRepository) -> None:
        self.repository = repository

    def normalize(
        self, text: str, hospital_id: str | None = None
    ) -> TermNormalizationResult:
        started = time.perf_counter()
        original = str(text or "")
        release = self.repository.active_release() or {}
        release_version = str(release.get("release_id") or "unreleased")
        entries = self._entries(hospital_id, release_version)
        selected, ambiguities = _select_matches(original, entries)
        replacements: list[tuple[int, int, str]] = []
        matches: list[TermMatch] = []
        for start, end, entry in selected:
            relation = str(entry["relation_type"])
            match = TermMatch(
                matched_text=original[start:end],
                concept_code=str(entry["concept_code"]),
                canonical_name=str(entry["canonical_name"]),
                relation_type=relation,
                retrieval_enabled=bool(entry["retrieval_enabled"]),
                sql_safe=bool(entry["sql_safe"]),
                source=str(entry["source"]),
                linked_rule_ids=list(entry["linked_rule_ids"]),
                business_field_keys=list(entry["business_field_keys"]),
            )
            matches.append(match)
            if relation in {"exact", "abbreviation", "colloquial", "value_mapping"}:
                replacements.append((start, end, match.canonical_name))
        normalized = original
        for start, end, replacement in sorted(replacements, reverse=True):
            normalized = normalized[:start] + replacement + normalized[end:]
        unsafe = any(item.relation_type in {"related", "forbidden"} for item in matches)
        return TermNormalizationResult(
            original_text=original,
            normalized_text=normalized,
            matches=matches,
            ambiguities=ambiguities,
            release_version=release_version,
            duration_ms=max(0, int((time.perf_counter() - started) * 1000)),
            sql_eligible=bool(matches) and not ambiguities and not unsafe,
        )

    def warm(self) -> None:
        self.normalize("", None)

    def _entries(
        self, hospital_id: str | None, release_version: str
    ) -> list[dict[str, Any]]:
        entries = list(self._base_entries(release_version))
        if not hospital_id:
            return entries
        representatives: dict[str, dict[str, Any]] = {}
        for entry in entries:
            representatives.setdefault(str(entry["concept_code"]), entry)
        for mapping in self.repository.active_hospital_mappings(hospital_id):
            representative = representatives.get(str(mapping["concept_code"]))
            if representative is None:
                continue
            for value in {str(mapping["local_name"]), str(mapping["local_code"])} - {""}:
                entries.append(
                    {
                        **representative,
                        "text": value,
                        "relation_type": "value_mapping",
                        "retrieval_enabled": True,
                        "sql_safe": True,
                        "source": "hospital",
                    }
                )
        return entries

    def _base_entries(self, release_version: str) -> list[dict[str, Any]]:
        key = (self._cache_namespace(), release_version)
        with self._cache_lock:
            cached = self._corpus_cache.get(key)
        if cached is not None:
            return cached
        concepts = {item["concept_code"]: item for item in self.repository.list_concepts()}
        links: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for link in self.repository.list_rule_links():
            links[str(link["concept_code"])].append(link)
        aliases = self.repository.list_aliases("approved")
        safe_concepts = {
            str(item["concept_code"])
            for item in aliases
            if bool(item["sql_safe"]) and item["relation_type"] not in {"related", "forbidden"}
        }
        entries: list[dict[str, Any]] = []
        for code, concept in concepts.items():
            entries.append(
                _entry(
                    concept,
                    concept["canonical_name"],
                    "exact",
                    True,
                    code in safe_concepts,
                    "company",
                    links[code],
                )
            )
        for alias in aliases:
            concept = concepts.get(str(alias["concept_code"]))
            if concept is None:
                continue
            entries.append(
                _entry(
                    concept,
                    alias["alias_text"],
                    alias["relation_type"],
                    bool(alias["retrieval_enabled"]),
                    bool(alias["sql_safe"]),
                    "company",
                    links[str(alias["concept_code"])],
                )
            )
        with self._cache_lock:
            self._corpus_cache[key] = entries
        return entries


    def _cache_namespace(self) -> str:
        engine = self.repository.engine
        url = engine.url
        base = url.render_as_string(hide_password=True)
        if url.drivername.startswith("sqlite"):
            return f"{base}:{id(engine)}"
        return base


def _entry(
    concept: dict[str, Any], text: str, relation_type: str,
    retrieval_enabled: bool, sql_safe: bool, source: str,
    links: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "text": str(text),
        "concept_code": str(concept["concept_code"]),
        "canonical_name": str(concept["canonical_name"]),
        "relation_type": relation_type,
        "retrieval_enabled": retrieval_enabled,
        "sql_safe": sql_safe,
        "source": source,
        "linked_rule_ids": sorted({str(item["index_code"]) for item in links}),
        "business_field_keys": sorted(
            {str(item["business_field_key"]) for item in links if item.get("business_field_key")}
        ),
    }


def _select_matches(
    text: str, entries: list[dict[str, Any]]
) -> tuple[list[tuple[int, int, dict[str, Any]]], list[dict[str, object]]]:
    lowered = text.lower()
    candidates: list[tuple[int, int, dict[str, Any]]] = []
    for entry in entries:
        needle = str(entry["text"]).lower().strip()
        if not needle:
            continue
        offset = 0
        while True:
            start = lowered.find(needle, offset)
            if start < 0:
                break
            candidates.append((start, start + len(needle), entry))
            offset = start + max(1, len(needle))
    candidates.sort(
        key=lambda item: (
            -(item[1] - item[0]),
            item[0],
            0 if item[2]["source"] == "hospital" else 1,
            str(item[2]["concept_code"]),
        )
    )
    occupied: set[int] = set()
    selected: list[tuple[int, int, dict[str, Any]]] = []
    ambiguities: list[dict[str, object]] = []
    for start, end, entry in candidates:
        span = set(range(start, end))
        same_span = [
            item for item in candidates
            if item[0] == start and item[1] == end
            and item[2]["concept_code"] != entry["concept_code"]
        ]
        if same_span:
            concepts = sorted(
                {str(entry["concept_code"]), *[str(item[2]["concept_code"]) for item in same_span]}
            )
            if not any(item.get("concept_codes") == concepts for item in ambiguities):
                ambiguities.append(
                    {"text": text[start:end], "concept_codes": concepts}
                )
            continue
        if span & occupied:
            continue
        occupied.update(span)
        selected.append((start, end, entry))
    selected.sort(key=lambda item: item[0])
    return selected, ambiguities

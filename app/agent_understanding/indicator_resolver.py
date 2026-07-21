"""规则优先、语义召回、LLM 候选消歧的指标识别器。"""

from __future__ import annotations

from collections import defaultdict
from difflib import SequenceMatcher
import json
import re
import time
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.agent_runtime.model_adapter import AgentModelAdapter, AgentModelError
from app.prompts import format_prompt, prompt_version
from app.terminology.normalizer import TerminologyNormalizer


MatchSource = Literal["rule", "semantic", "llm_disambiguation"]


class _Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ResolvedIndicator(_Contract):
    mention: str
    canonical_name: str
    rule_id: str
    concept_code: str
    source: MatchSource
    confidence: float = Field(ge=0.0, le=1.0)
    start: int = Field(default=0, ge=0)
    end: int = Field(default=0, ge=0)


class IndicatorAmbiguity(_Contract):
    mention: str
    candidates: list[dict[str, Any]] = Field(default_factory=list)


class IndicatorResolution(_Contract):
    indicators: list[ResolvedIndicator] = Field(default_factory=list)
    ambiguities: list[IndicatorAmbiguity] = Field(default_factory=list)
    used_llm: bool = False
    release_version: str = ""

    @property
    def needs_clarification(self) -> bool:
        return bool(self.ambiguities)


class _LLMSelection(_Contract):
    group_id: str
    rule_id: str | None = None


class _LLMSelections(_Contract):
    selections: list[_LLMSelection] = Field(default_factory=list)


_SEGMENT_SPLIT = re.compile(
    r"[,，、;；]|(?:还有|以及|另外(?:再|还|也)?|同时(?:还|也)?)"
)
_ACTION_PREFIX = re.compile(
    r"^(?:请|帮我|给我|再|同时|分别|查询|查一下|计算|算一下|统计|查看|看看)+"
)
_ACTION_SUFFIX = re.compile(
    r"(?:的)?(?:定义|公式|口径|具体结果|指标结果|结果|数值|指标值|分子分母)"
    r"?(?:怎么(?:算|计算|写)|如何(?:算|计算)|是多少|是什么|什么意思|给我)?[？?。]*$"
)
_TIME_TAIL = re.compile(
    r"(?:从|自|在)?(?:\d{2,4}\s*年)?(?:1[0-2]|[1-9]|[一二三四五六七八九十]{1,3})"
    r"\s*月份?.*$"
)
_INDICATOR_HINT = re.compile(
    r"指标|率|比例|会诊|转科|查房|患者|住院|手术|抢救|死亡|感染|输血"
)


def _compact(text: str) -> str:
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", str(text or "").lower())


def _clean_segment(text: str) -> str:
    value = _ACTION_PREFIX.sub("", str(text or "").strip())
    value = _TIME_TAIL.sub("", value).strip()
    value = _ACTION_SUFFIX.sub("", value).strip()
    return value.strip(" \t\r\n,，、;；。？?")


def _candidate_segments(query: str) -> list[tuple[int, str]]:
    """先按强分隔符切分，再仅在左右都像指标时接受“和/与”。"""
    result: list[tuple[int, str]] = []
    offset = 0
    for part in _SEGMENT_SPLIT.split(str(query or "")):
        raw = part.strip()
        if not raw:
            continue
        start = str(query).find(raw, offset)
        start = max(0, start)
        offset = start + len(raw)
        conjunction_parts = re.split(r"和|与", raw)
        cleaned_parts = [_clean_segment(value) for value in conjunction_parts]
        if (
            len(cleaned_parts) == 2
            and all(value and _INDICATOR_HINT.search(value) for value in cleaned_parts)
        ):
            local_offset = 0
            for original, cleaned in zip(conjunction_parts, cleaned_parts):
                local_start = raw.find(original, local_offset)
                local_offset = local_start + len(original)
                result.append((start + max(0, local_start), cleaned))
            continue
        cleaned = _clean_segment(raw)
        if cleaned and _INDICATOR_HINT.search(cleaned):
            result.append((start, cleaned))
    return result


def _ngrams(text: str, size: int = 2) -> set[str]:
    compact = _compact(text)
    if len(compact) <= size:
        return {compact} if compact else set()
    return {compact[index:index + size] for index in range(len(compact) - size + 1)}


def _semantic_score(query: str, candidate: str) -> float:
    left = _compact(query)
    right = _compact(candidate)
    if not left or not right:
        return 0.0
    sequence = SequenceMatcher(None, left, right).ratio()
    left_grams = _ngrams(left)
    right_grams = _ngrams(right)
    union = left_grams | right_grams
    jaccard = len(left_grams & right_grams) / len(union) if union else 0.0
    containment = min(len(left), len(right)) / max(len(left), len(right)) if (
        left in right or right in left
    ) else 0.0
    return round(max(sequence, jaccard, containment), 4)


class HybridIndicatorResolver:
    """只识别指标身份；不决定工具、SQL 或数据库执行。"""

    def __init__(
        self,
        terminology: TerminologyNormalizer,
        *,
        adapter: AgentModelAdapter | None = None,
        trace_callback=None,
        semantic_threshold: float = 0.68,
        semantic_margin: float = 0.12,
        max_indicators: int = 3,
    ) -> None:
        self.terminology = terminology
        self.adapter = adapter
        self.trace_callback = trace_callback
        self.semantic_threshold = semantic_threshold
        self.semantic_margin = semantic_margin
        self.max_indicators = max_indicators

    def _trace(self, **payload: Any) -> None:
        if self.trace_callback is None:
            return
        try:
            self.trace_callback({"event": "trace_node", **payload})
        except Exception:
            return

    def _catalog(self, hospital_id: str) -> list[dict[str, Any]]:
        repository = self.terminology.repository
        concepts = {
            str(item["concept_code"]): item
            for item in repository.list_concepts()
            if str(item.get("concept_type") or "") == "indicator"
        }
        aliases: dict[str, list[str]] = defaultdict(list)
        for item in repository.list_aliases("approved"):
            code = str(item.get("concept_code") or "")
            if code in concepts and bool(item.get("retrieval_enabled", True)):
                aliases[code].append(str(item.get("alias_text") or ""))
        for item in repository.list_hospital_aliases(hospital_id, "approved"):
            code = str(item.get("concept_code") or "")
            if code in concepts and bool(item.get("retrieval_enabled", True)):
                aliases[code].append(str(item.get("alias_text") or ""))
        rule_ids: dict[str, set[str]] = defaultdict(set)
        for item in repository.list_rule_links():
            code = str(item.get("concept_code") or "")
            if code in concepts:
                rule_ids[code].add(str(item.get("index_code") or ""))
        return [
            {
                "concept_code": code,
                "canonical_name": str(concept.get("canonical_name") or ""),
                "definition": str(concept.get("definition") or ""),
                "aliases": sorted({value for value in aliases[code] if value}),
                "rule_ids": sorted(value for value in rule_ids[code] if value),
            }
            for code, concept in concepts.items()
            if rule_ids[code]
        ]

    @staticmethod
    def _deduplicate(items: list[ResolvedIndicator]) -> list[ResolvedIndicator]:
        best: dict[str, ResolvedIndicator] = {}
        for item in items:
            current = best.get(item.rule_id)
            if current is None or (item.start, -item.confidence) < (
                current.start,
                -current.confidence,
            ):
                best[item.rule_id] = item
        return sorted(best.values(), key=lambda item: (item.start, item.end, item.rule_id))

    async def resolve(self, query: str, hospital_id: str) -> IndicatorResolution:
        started = time.perf_counter()
        normalization = self.terminology.normalize(query, hospital_id)
        catalog = self._catalog(hospital_id)
        catalog_by_code = {item["concept_code"]: item for item in catalog}
        resolved: list[ResolvedIndicator] = []
        ambiguities: list[IndicatorAmbiguity] = []
        cursor = 0
        occupied: list[tuple[int, int]] = []

        for match in normalization.matches:
            catalog_item = catalog_by_code.get(match.concept_code)
            if catalog_item is None or not match.linked_rule_ids:
                continue
            start = str(query).find(match.matched_text, cursor)
            if start < 0:
                start = str(query).find(match.matched_text)
            start = max(0, start)
            end = start + len(match.matched_text)
            cursor = end
            rule_ids = sorted(set(match.linked_rule_ids))
            if len(rule_ids) == 1:
                resolved.append(ResolvedIndicator(
                    mention=match.matched_text,
                    canonical_name=match.canonical_name,
                    rule_id=rule_ids[0],
                    concept_code=match.concept_code,
                    source="rule",
                    confidence=1.0,
                    start=start,
                    end=end,
                ))
                occupied.append((start, end))
            else:
                ambiguities.append(IndicatorAmbiguity(
                    mention=match.matched_text,
                    candidates=[
                        {
                            "rule_id": rule_id,
                            "canonical_name": match.canonical_name,
                            "concept_code": match.concept_code,
                            "score": 1.0,
                        }
                        for rule_id in rule_ids
                    ],
                ))

        rule_ms = max(1, int((time.perf_counter() - started) * 1000))
        self._trace(
            node_name="indicator_rule_match",
            node_type="code",
            status="success",
            duration_ms=rule_ms,
            input_data={"query": query, "hospital_id": hospital_id},
            output_data={
                "matches": [item.model_dump(mode="json") for item in resolved],
                "normalization_ambiguities": normalization.ambiguities,
            },
            processing_data={"description": "先用正式名称、简称和已审核别名精确识别指标。"},
            config_data={"release_version": normalization.release_version},
        )

        semantic_started = time.perf_counter()
        segments: list[tuple[int, str]] = []
        for start, cleaned in _candidate_segments(query):
            if any(
                start < end and start + len(cleaned) > begin
                for begin, end in occupied
            ):
                continue
            segments.append((start, cleaned))

        semantic_ambiguities: list[IndicatorAmbiguity] = []
        known_rules = {item.rule_id for item in resolved}
        for start, mention in segments:
            ranked: list[dict[str, Any]] = []
            for item in catalog:
                names = [item["canonical_name"], *item["aliases"]]
                score = max((_semantic_score(mention, name) for name in names), default=0.0)
                for rule_id in item["rule_ids"]:
                    if rule_id not in known_rules:
                        ranked.append({
                            "rule_id": rule_id,
                            "canonical_name": item["canonical_name"],
                            "concept_code": item["concept_code"],
                            "score": score,
                        })
            ranked.sort(key=lambda item: (-item["score"], item["rule_id"]))
            if not ranked:
                continue
            top = ranked[0]
            runner_up = ranked[1]["score"] if len(ranked) > 1 else 0.0
            if top["score"] >= self.semantic_threshold and (
                top["score"] - runner_up >= self.semantic_margin
            ):
                resolved.append(ResolvedIndicator(
                    mention=mention,
                    canonical_name=top["canonical_name"],
                    rule_id=top["rule_id"],
                    concept_code=top["concept_code"],
                    source="semantic",
                    confidence=top["score"],
                    start=start,
                    end=start + len(mention),
                ))
                known_rules.add(top["rule_id"])
            elif top["score"] >= 0.45:
                semantic_ambiguities.append(IndicatorAmbiguity(
                    mention=mention,
                    candidates=ranked[:3],
                ))

        self._trace(
            node_name="indicator_semantic_retrieval",
            node_type="code",
            status="success",
            duration_ms=max(1, int((time.perf_counter() - semantic_started) * 1000)),
            input_data={"segments": [value for _, value in segments]},
            output_data={
                "resolved": [item.model_dump(mode="json") for item in resolved],
                "candidate_groups": [item.model_dump(mode="json") for item in semantic_ambiguities],
            },
            processing_data={"description": "对规则未命中的疑似指标片段做本地字符语义相似度召回。"},
            config_data={
                "algorithm": "sequence+jaccard+containment",
                "threshold": self.semantic_threshold,
                "margin": self.semantic_margin,
            },
        )

        unresolved = [*ambiguities, *semantic_ambiguities]
        used_llm = False
        if unresolved and self.adapter is not None:
            used_llm = True
            selected, remaining = await self._disambiguate(query, unresolved)
            resolved.extend(selected)
            unresolved = remaining

        resolved = self._deduplicate(resolved)
        if len(resolved) > self.max_indicators:
            unresolved.append(IndicatorAmbiguity(
                mention=query,
                candidates=[
                    {
                        "rule_id": item.rule_id,
                        "canonical_name": item.canonical_name,
                        "concept_code": item.concept_code,
                        "score": item.confidence,
                    }
                    for item in resolved
                ],
            ))
            resolved = []
        return IndicatorResolution(
            indicators=resolved,
            ambiguities=unresolved,
            used_llm=used_llm,
            release_version=normalization.release_version,
        )

    async def _disambiguate(
        self,
        query: str,
        groups: list[IndicatorAmbiguity],
    ) -> tuple[list[ResolvedIndicator], list[IndicatorAmbiguity]]:
        group_map = {f"candidate_{index}": group for index, group in enumerate(groups, 1)}
        candidate_payload = [
            {
                "group_id": group_id,
                "mention": group.mention,
                "candidates": group.candidates,
            }
            for group_id, group in group_map.items()
        ]
        messages = [{
            "role": "system",
            "content": format_prompt(
                "indicator_candidate_disambiguator",
                candidate_groups=json.dumps(candidate_payload, ensure_ascii=False),
            ),
        }, {"role": "user", "content": query}]
        started = time.perf_counter()
        raw_content = ""
        model_id = None
        try:
            response = await self.adapter.chat(
                messages=messages,
                tools=[],
                temperature=0.0,
            )
            raw_content = str(response.content or "").strip()
            model_id = response.model
            fenced = re.fullmatch(
                r"```(?:json)?\s*(.*?)\s*```",
                raw_content,
                re.DOTALL | re.IGNORECASE,
            )
            if fenced:
                raw_content = fenced.group(1)
            selections = _LLMSelections.model_validate(json.loads(raw_content))
        except (AgentModelError, json.JSONDecodeError, ValidationError, TypeError):
            self._trace(
                node_name="indicator_llm_disambiguation",
                node_type="llm",
                status="failed",
                duration_ms=max(1, int((time.perf_counter() - started) * 1000)),
                input_data={"messages": messages, "tools": [], "temperature": 0.0},
                output_data={"raw_content": raw_content},
                processing_data={"description": "模型输出无效时保留候选并交由用户澄清。"},
                config_data={
                    "prompt_file": "indicator_candidate_disambiguator.txt",
                    "prompt_version": prompt_version("indicator_candidate_disambiguator"),
                    "model_id": model_id,
                },
            )
            return [], groups

        resolved: list[ResolvedIndicator] = []
        resolved_groups: set[str] = set()
        for selection in selections.selections:
            group = group_map.get(selection.group_id)
            if group is None or selection.rule_id is None:
                continue
            candidate = next(
                (
                    item for item in group.candidates
                    if str(item.get("rule_id")) == selection.rule_id
                ),
                None,
            )
            if candidate is None:
                continue
            resolved_groups.add(selection.group_id)
            resolved.append(ResolvedIndicator(
                mention=group.mention,
                canonical_name=str(candidate["canonical_name"]),
                rule_id=str(candidate["rule_id"]),
                concept_code=str(candidate["concept_code"]),
                source="llm_disambiguation",
                confidence=float(candidate.get("score") or 0.5),
                start=max(0, query.find(group.mention)),
                end=max(0, query.find(group.mention)) + len(group.mention),
            ))
        remaining = [
            group
            for group_id, group in group_map.items()
            if group_id not in resolved_groups
        ]
        self._trace(
            node_name="indicator_llm_disambiguation",
            node_type="llm",
            status="success",
            duration_ms=max(1, int((time.perf_counter() - started) * 1000)),
            input_data={"messages": messages, "tools": [], "temperature": 0.0},
            output_data={
                "raw_content": raw_content,
                "resolved": [item.model_dump(mode="json") for item in resolved],
                "remaining_groups": len(remaining),
            },
            processing_data={"description": "只允许模型在服务端候选 rule_id 中消歧，禁止生成新指标。"},
            config_data={
                "prompt_file": "indicator_candidate_disambiguator.txt",
                "prompt_version": prompt_version("indicator_candidate_disambiguator"),
                "model_id": model_id,
            },
        )
        return resolved, remaining

from __future__ import annotations

import asyncio

from app.agent_runtime.contracts import AgentModelResponse
from app.agent_understanding import HybridIndicatorResolver
from app.terminology.contracts import TermMatch, TermNormalizationResult


class FakeRepository:
    def __init__(self, *, similar=False):
        self.similar = similar

    def list_concepts(self):
        second_name = (
            "急会诊及时到达率"
            if self.similar
            else "患者入院 48 小时内转科的比例"
        )
        return [
            {
                "concept_code": "C1",
                "concept_type": "indicator",
                "canonical_name": "急会诊及时到位率",
                "definition": "急会诊及时到达现场的比例",
            },
            {
                "concept_code": "C2",
                "concept_type": "indicator",
                "canonical_name": second_name,
                "definition": "另一个核心制度指标",
            },
        ]

    def list_aliases(self, approval_status="approved"):
        del approval_status
        return [
            {
                "concept_code": "C1",
                "alias_text": "急会诊到位率",
                "retrieval_enabled": True,
            },
            {
                "concept_code": "C2",
                "alias_text": "48小时转科比例",
                "retrieval_enabled": True,
            },
        ]

    def list_hospital_aliases(self, hospital_id, approval_status="approved"):
        del hospital_id, approval_status
        return []

    def list_rule_links(self):
        return [
            {"concept_code": "C1", "index_code": "RULE_1"},
            {"concept_code": "C2", "index_code": "RULE_2"},
        ]


class FakeTerminology:
    def __init__(self, repository, matches=None):
        self.repository = repository
        self.matches = list(matches or [])

    def normalize(self, text, hospital_id=None):
        del hospital_id
        return TermNormalizationResult(
            original_text=text,
            normalized_text=text,
            matches=self.matches,
            release_version="test-v1",
        )


class StaticAdapter:
    def __init__(self, content):
        self.content = content
        self.calls = []

    async def chat(self, **kwargs):
        self.calls.append(kwargs)
        return AgentModelResponse(content=self.content, model="test-model")


def _match(text, code, name, rule_id):
    return TermMatch(
        matched_text=text,
        concept_code=code,
        canonical_name=name,
        relation_type="exact",
        retrieval_enabled=True,
        sql_safe=True,
        linked_rule_ids=[rule_id],
    )


def test_rule_layer_recognizes_two_indicators_without_explicit_connector():
    terminology = FakeTerminology(FakeRepository(), matches=[
        _match("急会诊及时到位率", "C1", "急会诊及时到位率", "RULE_1"),
        _match(
            "患者入院 48 小时内转科的比例",
            "C2",
            "患者入院 48 小时内转科的比例",
            "RULE_2",
        ),
    ])
    resolver = HybridIndicatorResolver(terminology)

    result = asyncio.run(resolver.resolve(
        "急会诊及时到位率，患者入院 48 小时内转科的比例怎么算？",
        "hospital_001",
    ))

    assert [item.rule_id for item in result.indicators] == ["RULE_1", "RULE_2"]
    assert [item.source for item in result.indicators] == ["rule", "rule"]
    assert not result.needs_clarification


def test_semantic_layer_resolves_imprecise_indicator_name_without_llm():
    resolver = HybridIndicatorResolver(FakeTerminology(FakeRepository()))

    result = asyncio.run(resolver.resolve(
        "帮我看看患者入院48小时转科比例怎么算",
        "hospital_001",
    ))

    assert len(result.indicators) == 1
    assert result.indicators[0].rule_id == "RULE_2"
    assert result.indicators[0].source == "semantic"
    assert not result.used_llm


def test_semantic_layer_splits_two_imprecise_names_joined_by_and():
    resolver = HybridIndicatorResolver(FakeTerminology(FakeRepository()))

    result = asyncio.run(resolver.resolve(
        "急会诊到位率和48小时转科比例怎么算",
        "hospital_001",
    ))

    assert [item.rule_id for item in result.indicators] == ["RULE_1", "RULE_2"]
    assert all(item.source == "semantic" for item in result.indicators)
    assert not result.needs_clarification


def test_llm_can_only_select_rule_id_from_semantic_candidate_group():
    adapter = StaticAdapter(
        '{"selections":[{"group_id":"candidate_1","rule_id":"RULE_1"}]}'
    )
    resolver = HybridIndicatorResolver(
        FakeTerminology(FakeRepository(similar=True)),
        adapter=adapter,
        semantic_threshold=0.99,
    )

    result = asyncio.run(resolver.resolve("急会诊及时率怎么算", "hospital_001"))

    assert result.used_llm
    assert [item.rule_id for item in result.indicators] == ["RULE_1"]
    assert result.indicators[0].source == "llm_disambiguation"
    assert adapter.calls[0]["tools"] == []


def test_llm_invented_rule_id_is_rejected_and_requires_clarification():
    adapter = StaticAdapter(
        '{"selections":[{"group_id":"candidate_1","rule_id":"INVENTED"}]}'
    )
    resolver = HybridIndicatorResolver(
        FakeTerminology(FakeRepository(similar=True)),
        adapter=adapter,
        semantic_threshold=0.99,
    )

    result = asyncio.run(resolver.resolve("急会诊及时率怎么算", "hospital_001"))

    assert result.indicators == []
    assert result.needs_clarification

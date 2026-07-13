"""医学术语库的结构化输入输出契约。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, model_validator


RelationType = Literal[
    "exact",
    "abbreviation",
    "colloquial",
    "related",
    "value_mapping",
    "forbidden",
]
ApprovalStatus = Literal["pending", "approved", "rejected"]


class TermAlias(BaseModel):
    concept_code: str = ""
    alias_text: str
    relation_type: RelationType
    retrieval_enabled: bool = True
    sql_safe: bool = False
    ambiguity_group: str | None = None
    source_reference: str = ""
    approval_status: ApprovalStatus = "approved"
    version: int = 1

    @model_validator(mode="after")
    def reject_unsafe_relations(self) -> "TermAlias":
        if self.sql_safe and self.relation_type in {"related", "forbidden"}:
            raise ValueError("相关词或禁止替换词不能用于 SQL")
        return self


class TermRuleLink(BaseModel):
    concept_code: str = ""
    index_code: str
    usage_section: Literal[
        "rule_name", "definition", "numerator", "denominator", "filter", "exclude"
    ]
    business_field_key: str | None = None
    source_reference: str = ""
    version: int = 1


class TermConcept(BaseModel):
    concept_code: str
    canonical_name: str
    concept_type: Literal[
        "indicator",
        "diagnosis",
        "department",
        "staff_role",
        "procedure",
        "time_window",
        "status",
        "data_value",
        "business_concept",
    ]
    definition: str = ""
    standard_code: str | None = None
    source_level: Literal["national", "company"] = "national"
    source_reference: str = ""
    version: int = 1
    status: Literal["draft", "active", "inactive"] = "active"
    aliases: list[TermAlias] = Field(default_factory=list)

    @model_validator(mode="after")
    def fill_alias_concept_codes(self) -> "TermConcept":
        for alias in self.aliases:
            if alias.concept_code and alias.concept_code != self.concept_code:
                raise ValueError("别名 concept_code 与所属概念不一致")
            alias.concept_code = self.concept_code
        return self


class RuleCoverage(BaseModel):
    concept_links: list[TermRuleLink] = Field(default_factory=list)
    covered_sections: list[str] = Field(default_factory=list)
    not_applicable_sections: list[str] = Field(default_factory=list)
    review_status: ApprovalStatus = "pending"
    reviewed_by: str = ""


class TermCorpus(BaseModel):
    schema_version: Literal["term-corpus-v1"]
    concepts: list[TermConcept]
    rule_coverage: dict[str, RuleCoverage]


class CoverageReport(BaseModel):
    ok: bool
    rule_count: int
    concept_count: int
    alias_count: int
    pending_count: int
    errors: list[str] = Field(default_factory=list)


class HospitalTermMapping(BaseModel):
    hospital_id: str
    concept_code: str
    code_system: str
    local_code: str = ""
    local_name: str
    local_value: str
    approval_status: ApprovalStatus = "pending"
    version: int = 1


class TermMatch(BaseModel):
    matched_text: str
    concept_code: str
    canonical_name: str
    relation_type: RelationType
    retrieval_enabled: bool
    sql_safe: bool
    source: Literal["hospital", "company", "wiki"] = "company"
    linked_rule_ids: list[str] = Field(default_factory=list)
    business_field_keys: list[str] = Field(default_factory=list)


class TermNormalizationResult(BaseModel):
    original_text: str
    normalized_text: str
    matches: list[TermMatch] = Field(default_factory=list)
    ambiguities: list[dict[str, object]] = Field(default_factory=list)
    release_version: str = ""
    duration_ms: int = 0
    sql_eligible: bool = False

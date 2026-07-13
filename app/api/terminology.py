"""医学术语维护工作台 API。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel, Field


router = APIRouter(prefix="/api/terminology", tags=["terminology"])


@dataclass
class TerminologyContext:
    repository: Any
    normalizer: Any
    releases: Any


class RecognitionTestRequest(BaseModel):
    hospital_id: str = "hospital_001"
    text: str = Field(min_length=1, max_length=1000)


class AliasCreateRequest(BaseModel):
    hospital_id: str = ""
    concept_code: str
    alias_text: str = Field(min_length=1, max_length=200)
    relation_type: Literal[
        "exact", "abbreviation", "colloquial", "related", "forbidden"
    ]
    retrieval_enabled: bool = True
    sql_safe: bool = False
    ambiguity_group: str | None = None
    source_reference: str = "manual"
    created_by: str = "admin"


class HospitalMappingCreateRequest(BaseModel):
    hospital_id: str
    concept_code: str
    code_system: str
    local_code: str = ""
    local_name: str = Field(min_length=1, max_length=200)
    local_value: str = Field(min_length=1, max_length=500)
    effective_from: str | None = None
    effective_to: str | None = None
    created_by: str = "admin"


class ActorRequest(BaseModel):
    actor_id: str = "admin"


def _create_terminology_context() -> TerminologyContext:
    from app.db.engine import create_runtime_engine
    from app.terminology.normalizer import TerminologyNormalizer
    from app.terminology.release import TerminologyReleaseService
    from app.terminology.repository import TerminologyRepository

    repository = TerminologyRepository(create_runtime_engine())
    return TerminologyContext(
        repository=repository,
        normalizer=TerminologyNormalizer(repository),
        releases=TerminologyReleaseService(repository),
    )


def _require_admin(authorization: str | None = Header(None)) -> str:
    from app.api.main import _require_admin as main_require_admin

    return main_require_admin(authorization)


def _http_error(exc: Exception) -> HTTPException:
    code = str(exc)
    status = 404 if "NOT_FOUND" in code else 409
    messages = {
        "TERM_ALIAS_NOT_FOUND": "未找到该候选词。",
        "TERM_ALIAS_CONFLICT": "该词已指向其他概念，请先设置歧义分组。",
        "TERM_ALIAS_SQL_UNSAFE": "相关词或禁止替换词不能用于 SQL。",
        "TERM_MAPPING_NOT_FOUND": "未找到该医院术语映射。",
    }
    return HTTPException(status_code=status, detail=messages.get(code, code))


@router.get("/concepts")
def list_concepts(
    query: str = "",
    concept_type: str = "",
    rule_id: str = "",
) -> dict[str, Any]:
    context = _create_terminology_context()
    aliases = context.repository.list_aliases("approved")
    alias_by_code: dict[str, list[str]] = {}
    for item in aliases:
        alias_by_code.setdefault(str(item["concept_code"]), []).append(
            str(item["alias_text"])
        )
    linked_codes = {
        str(item["concept_code"])
        for item in context.repository.list_rule_links()
        if not rule_id or str(item["index_code"]) == rule_id
    }
    needle = query.strip().lower()
    items = []
    for concept in context.repository.list_concepts():
        code = str(concept["concept_code"])
        names = [str(concept["canonical_name"]), *alias_by_code.get(code, [])]
        if needle and not any(needle in name.lower() for name in names):
            continue
        if concept_type and str(concept["concept_type"]) != concept_type:
            continue
        if rule_id and code not in linked_codes:
            continue
        items.append(
            {
                **concept,
                "alias_count": len(alias_by_code.get(code, [])),
                "aliases_preview": alias_by_code.get(code, [])[:3],
            }
        )
    return {"items": items, "total": len(items)}


@router.get("/concepts/{concept_code}")
def get_concept(
    concept_code: str,
    hospital_id: str = Query(default="hospital_001"),
) -> dict[str, Any]:
    context = _create_terminology_context()
    concept = context.repository.get_concept(concept_code)
    if concept is None:
        raise HTTPException(status_code=404, detail="未找到该标准概念。")
    return {
        **concept,
        "aliases": context.repository.concept_aliases(
            concept_code, hospital_id=hospital_id
        ),
        "rule_links": context.repository.concept_rule_links(concept_code),
        "hospital_id": hospital_id,
        "hospital_mappings": context.repository.list_hospital_mappings(
            hospital_id, concept_code
        ),
        "active_release": context.repository.active_release(),
    }


@router.post("/test")
def test_recognition(body: RecognitionTestRequest) -> dict[str, Any]:
    return _create_terminology_context().normalizer.normalize(
        body.text, body.hospital_id
    ).model_dump(exclude_none=True)


@router.post("/aliases")
def create_alias(
    body: AliasCreateRequest,
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    try:
        return _create_terminology_context().repository.create_alias_candidate(
            body.model_dump(exclude_none=True)
        )
    except (LookupError, ValueError) as exc:
        raise _http_error(exc) from exc


@router.post("/aliases/{alias_id}/approve")
def approve_alias(
    alias_id: int,
    body: ActorRequest,
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    try:
        return _create_terminology_context().repository.approve_alias(
            alias_id, body.actor_id
        )
    except (LookupError, ValueError) as exc:
        raise _http_error(exc) from exc


@router.post("/hospital-mappings")
def create_hospital_mapping(
    body: HospitalMappingCreateRequest,
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    return _create_terminology_context().repository.create_hospital_mapping_candidate(
        body.model_dump(exclude_none=True)
    )


@router.post("/hospital-mappings/{mapping_id}/approve")
def approve_hospital_mapping(
    mapping_id: int,
    body: ActorRequest,
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    try:
        return _create_terminology_context().repository.approve_hospital_mapping(
            mapping_id, body.actor_id
        )
    except (LookupError, ValueError) as exc:
        raise _http_error(exc) from exc


@router.get("/releases")
def list_releases() -> dict[str, Any]:
    items = _create_terminology_context().repository.list_releases()
    for item in items:
        item.pop("snapshot_json", None)
    return {"items": items}


@router.post("/releases/publish")
def publish_release(
    body: ActorRequest,
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    return _create_terminology_context().releases.publish(body.actor_id)


@router.post("/releases/{release_id}/restore")
def restore_release(
    release_id: str,
    body: ActorRequest,
    authorization: str | None = Header(None, alias="Authorization"),
) -> dict[str, Any]:
    _require_admin(authorization)
    try:
        return _create_terminology_context().releases.restore(
            release_id, body.actor_id
        )
    except (LookupError, ValueError) as exc:
        raise _http_error(exc) from exc

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel


router = APIRouter(prefix="/api/indicator-drafts", tags=["indicator-drafts"])
published_router = APIRouter(prefix="/api/hospital-defined", tags=["hospital-defined"])


class GenerateDraftRequest(BaseModel):
    query: str
    hospital_id: str = "hospital_001"
    actor_id: str = "operator"


class ReleaseAdaptationRequest(BaseModel):
    import_id: str
    rule_id: str
    hospital_id: str = "hospital_001"
    actor_id: str = "admin"


class UpdateDraftRequest(BaseModel):
    expected_version: int
    changes: dict[str, Any]
    actor_id: str = "operator"


class DraftActionRequest(BaseModel):
    expected_version: int
    actor_id: str = "operator"


class MetadataConfirmRequest(DraftActionRequest):
    mappings: dict[str, dict[str, Any]]


class TrialRunRequest(DraftActionRequest):
    stat_start_time: str
    stat_end_time: str


class ApproveDraftRequest(BaseModel):
    expected_version: int
    approver_id: str = "admin"


class RejectDraftRequest(ApproveDraftRequest):
    reason: str


class RestoreVersionRequest(BaseModel):
    approver_id: str = "admin"


@dataclass
class IndicatorDraftServices:
    indicator_generation: Any
    repository: Any
    metadata: Any
    workflow: Any
    publisher: Any


def _create_indicator_draft_services() -> IndicatorDraftServices:
    from app.agents.indicator_generation import IndicatorGenerationAgent
    from app.agents.metadata_parsing import MetadataParsingAgent
    from app.api.main import create_business_db_client
    from app.db.engine import create_runtime_engine
    from app.indicators.metadata import DraftMetadataResolver
    from app.indicators.parser import IndicatorDraftParser
    from app.indicators.publisher import HospitalIndicatorPublisher
    from app.indicators.repository import IndicatorDraftRepository
    from app.indicators.service import IndicatorDraftWorkflowService
    from app.kb.tools import DEFAULT_KB_ROOT
    from app.llm.ollama import OllamaClient

    engine = create_runtime_engine()
    repository = IndicatorDraftRepository(engine)
    resolver = DraftMetadataResolver(engine, repository)
    return IndicatorDraftServices(
        indicator_generation=IndicatorGenerationAgent(
            None,
            draft_parser=IndicatorDraftParser(OllamaClient()),
            draft_repository=repository,
        ),
        repository=repository,
        metadata=MetadataParsingAgent(
            engine,
            DEFAULT_KB_ROOT,
            draft_metadata_resolver=resolver,
        ),
        workflow=IndicatorDraftWorkflowService(
            runtime_engine=engine,
            business_db=create_business_db_client("hospital_demo_data"),
            draft_repository=repository,
        ),
        publisher=HospitalIndicatorPublisher(engine, repository),
    )


def _require_admin(authorization: str | None = Header(None)) -> str:
    from app.api.main import _require_admin as main_require_admin

    return main_require_admin(authorization)


def _payload(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump(exclude_none=True)
    return value


def _call(action: Any) -> Any:
    from app.indicators.metadata import MetadataResolutionError
    from app.indicators.parser import DraftParseError
    from app.indicators.publisher import IndicatorPublishError
    from app.indicators.repository import DraftNotFoundError, DraftVersionConflict
    from app.indicators.service import DraftWorkflowError
    from app.llm.ollama import OllamaError

    try:
        return _payload(action())
    except DraftNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except (DraftVersionConflict,) as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except IndicatorPublishError as exc:
        message = str(exc)
        status = 404 if "NOT_FOUND" in message else 409 if "CONFLICT" in message else 400
        raise HTTPException(status_code=status, detail=message) from exc
    except OllamaError as exc:
        raise HTTPException(status_code=503, detail=f"本地模型不可用：{exc}") from exc
    except (DraftParseError, MetadataResolutionError, DraftWorkflowError, ValueError) as exc:
        message = str(exc)
        status = 409 if "版本" in message or "VERSION" in message else 400
        raise HTTPException(status_code=status, detail=message) from exc


@router.post("/generate")
def generate_indicator_draft(body: GenerateDraftRequest):
    return _call(
        lambda: _create_indicator_draft_services().indicator_generation.create_draft(
            body.query,
            body.hospital_id,
            body.actor_id,
        )
    )


@router.post("/from-release")
def generate_indicator_draft_from_release(
    body: ReleaseAdaptationRequest,
    _: str = Depends(_require_admin),
):
    services = _create_indicator_draft_services()
    adaptation = getattr(services, "release_adaptation", None)
    if adaptation is None:
        from app.api.main import _hospital_release_repository
        from app.indicators.release_adaptation import ReleaseAdaptationService

        adaptation = ReleaseAdaptationService(
            release_repository=_hospital_release_repository(),
            draft_repository=services.repository,
            generation_agent=services.indicator_generation,
        )
    return _call(
        lambda: adaptation.create(
            body.import_id,
            body.rule_id,
            body.hospital_id,
            body.actor_id,
        )
    )


@router.get("")
def list_indicator_drafts(hospital_id: str, status: str | None = None):
    return _call(
        lambda: _create_indicator_draft_services().repository.list(
            hospital_id, status=status
        )
    )


@router.get("/{draft_id}")
def get_indicator_draft(draft_id: str):
    return _call(
        lambda: _create_indicator_draft_services().repository.get(draft_id)
    )


@router.put("/{draft_id}")
def update_indicator_draft(draft_id: str, body: UpdateDraftRequest):
    return _call(
        lambda: _create_indicator_draft_services().repository.save_version(
            draft_id,
            body.expected_version,
            body.changes,
            body.actor_id,
        )
    )


@router.post("/{draft_id}/requirements-confirm")
def confirm_indicator_draft_requirements(
    draft_id: str, body: DraftActionRequest
):
    return _call(
        lambda: _create_indicator_draft_services().workflow.confirm_requirements(
            draft_id,
            body.expected_version,
            body.actor_id,
        )
    )


@router.get("/{draft_id}/metadata-suggestions")
def suggest_indicator_draft_metadata(draft_id: str):
    return _call(
        lambda: _create_indicator_draft_services().metadata.suggest_draft_fields(
            draft_id
        )
    )


@router.post("/{draft_id}/metadata-confirm")
def confirm_indicator_draft_metadata(
    draft_id: str, body: MetadataConfirmRequest
):
    return _call(
        lambda: _create_indicator_draft_services().metadata.confirm_draft_fields(
            draft_id,
            body.expected_version,
            body.mappings,
            body.actor_id,
        )
    )


@router.post("/{draft_id}/sql-generate")
def generate_indicator_draft_sql(draft_id: str, body: DraftActionRequest):
    return _call(
        lambda: _create_indicator_draft_services().workflow.generate_sql(
            draft_id, body.expected_version, body.actor_id
        )
    )


@router.post("/{draft_id}/trial-run")
def trial_run_indicator_draft(draft_id: str, body: TrialRunRequest):
    return _call(
        lambda: _create_indicator_draft_services().workflow.trial_run(
            draft_id,
            body.expected_version,
            body.stat_start_time,
            body.stat_end_time,
            body.actor_id,
        )
    )


@router.post("/{draft_id}/submit")
def submit_indicator_draft(draft_id: str, body: DraftActionRequest):
    return _call(
        lambda: _create_indicator_draft_services().workflow.submit(
            draft_id, body.expected_version, body.actor_id
        )
    )


@router.post("/{draft_id}/approve")
def approve_indicator_draft(
    draft_id: str,
    body: ApproveDraftRequest,
    _: str = Depends(_require_admin),
):
    return _call(
        lambda: _create_indicator_draft_services().publisher.approve(
            draft_id, body.expected_version, body.approver_id
        )
    )


@router.post("/{draft_id}/reject")
def reject_indicator_draft(
    draft_id: str,
    body: RejectDraftRequest,
    _: str = Depends(_require_admin),
):
    return _call(
        lambda: _create_indicator_draft_services().publisher.reject(
            draft_id,
            body.expected_version,
            body.approver_id,
            body.reason,
        )
    )


@published_router.get("/{hospital_id}/{index_code}/versions")
def list_hospital_defined_versions(
    hospital_id: str,
    index_code: str,
    _: str = Depends(_require_admin),
):
    return _call(
        lambda: _create_indicator_draft_services().publisher.list_versions(
            index_code, hospital_id
        )
    )


@published_router.post(
    "/{hospital_id}/{index_code}/versions/{version}/restore"
)
def restore_hospital_defined_version(
    hospital_id: str,
    index_code: str,
    version: int,
    body: RestoreVersionRequest,
    _: str = Depends(_require_admin),
):
    return _call(
        lambda: _create_indicator_draft_services().publisher.restore_version(
            index_code,
            hospital_id,
            version,
            body.approver_id,
        )
    )

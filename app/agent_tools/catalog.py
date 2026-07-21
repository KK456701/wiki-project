"""首批 Agent 工具的完整动态目录。"""

from __future__ import annotations

from app.agent_tools.diagnosis_tools import DiagnosisToolServices, build_diagnosis_tools
from app.agent_tools.implementation_validation_tools import (
    build_implementation_validation_tools,
)
from app.implementation_validation import ImplementationValidationServices
from app.agent_tools.preview_tools import PreviewToolServices, build_preview_tools
from app.agent_tools.read_tools import ReadToolServices, build_read_tools
from app.agent_tools.registry import ToolRegistry
from app.agent_tools.sql_tools import SqlToolServices, build_sql_tools
from app.agent_tools.upload_tools import UploadToolServices, build_upload_tools


def build_agent_tool_registry(
    *,
    read_services: ReadToolServices,
    sql_services: SqlToolServices,
    diagnosis_services: DiagnosisToolServices,
    preview_services: PreviewToolServices,
    upload_services: UploadToolServices | None = None,
    implementation_validation_services: ImplementationValidationServices | None = None,
) -> ToolRegistry:
    tools: list = [
        *build_read_tools(read_services),
        *build_sql_tools(sql_services),
        *build_diagnosis_tools(diagnosis_services),
        *build_preview_tools(preview_services),
    ]
    if upload_services is not None:
        tools.extend(build_upload_tools(upload_services))
    if implementation_validation_services is not None:
        tools.extend(
            build_implementation_validation_tools(
                implementation_validation_services
            )
        )
    return ToolRegistry(tools)

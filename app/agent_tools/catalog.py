"""首批 Agent 工具的完整动态目录。"""

from __future__ import annotations

from app.agent_tools.diagnosis_tools import DiagnosisToolServices, build_diagnosis_tools
from app.agent_tools.preview_tools import PreviewToolServices, build_preview_tools
from app.agent_tools.read_tools import ReadToolServices, build_read_tools
from app.agent_tools.registry import ToolRegistry
from app.agent_tools.sql_tools import SqlToolServices, build_sql_tools


def build_agent_tool_registry(
    *,
    read_services: ReadToolServices,
    sql_services: SqlToolServices,
    diagnosis_services: DiagnosisToolServices,
    preview_services: PreviewToolServices,
) -> ToolRegistry:
    return ToolRegistry([
        *build_read_tools(read_services),
        *build_sql_tools(sql_services),
        *build_diagnosis_tools(diagnosis_services),
        *build_preview_tools(preview_services),
    ])

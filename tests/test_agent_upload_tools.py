from __future__ import annotations

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools import upload_tools
from app.agent_tools.upload_tools import (
    AnalyzeUploadedIndicatorsInput,
    UploadToolServices,
    analyze_uploaded_indicators,
)


def test_successful_upload_analysis_emits_canonical_completion_fact(
    tmp_path,
    monkeypatch,
):
    file_key = "hospital_001_report.xlsx"
    (tmp_path / file_key).write_bytes(b"test")
    monkeypatch.setattr(upload_tools, "_UPLOAD_ROOT", tmp_path)
    monkeypatch.setattr(
        upload_tools,
        "_parse_excel_preview",
        lambda _path: {
            "file_name": file_key,
            "sheet_count": 1,
            "total_rows": 2,
            "sheets": [{
                "sheet_name": "Sheet1",
                "headers": ["指标名称", "指标率"],
                "row_count": 2,
            }],
            "indicator_hints": {},
        },
    )
    context = AgentRuntimeContext(
        user_id="u1",
        hospital_id="hospital_001",
        session_id="s1",
        user_role="implementer",
        permissions=frozenset(),
        request_id="req1",
        trace_id="trace1",
    )

    result = analyze_uploaded_indicators(
        AnalyzeUploadedIndicatorsInput(file_key=file_key),
        context,
        AgentRunState(),
        UploadToolServices(),
    )

    assert result.ok is True
    assert result.evidence[0].fact_types == ["file_analysis"]

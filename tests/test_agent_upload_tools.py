from __future__ import annotations

from app.agent_runtime.contracts import AgentRunState, AgentRuntimeContext
from app.agent_tools import upload_tools
from app.agent_tools.upload_tools import (
    AnalyzeUploadedIndicatorsInput,
    UploadToolServices,
    analyze_uploaded_indicators,
    build_row_level_comparison,
    parse_excel_preview,
)
from openpyxl import Workbook


def test_successful_upload_analysis_emits_canonical_completion_fact(
    tmp_path,
    monkeypatch,
):
    file_key = "hospital_001_report.xlsx"
    (tmp_path / file_key).write_bytes(b"test")
    monkeypatch.setattr(upload_tools, "_UPLOAD_ROOT", tmp_path)
    monkeypatch.setattr(
        upload_tools,
        "parse_excel_preview",
        lambda _path: {
            "file_name": file_key,
            "sheet_count": 1,
            "total_rows": 2,
            "sheets": [{
                "sheet_name": "Sheet1",
                "headers": ["指标名称", "指标率"],
                "row_count": 2,
            }],
            "indicator_hints": {
                "potential_indicator_values": [
                    {"column": "分母", "role": "denominator", "stats": {"avg": 522}},
                    {"column": "分子", "role": "numerator", "stats": {"avg": 30}},
                    {"column": "指标率", "role": "rate", "stats": {"avg": 5.75}},
                ],
            },
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
        AgentRunState(last_tool_results=[{
            "ok": True,
            "code": "TRIAL_RUN_COMPLETED",
            "data": {
                "stat_start": "2026-01-01 00:00:00",
                "stat_end": "2026-07-17 00:00:00",
                "numerator_count": 11,
                "denominator_count": 389,
                "result_value": 2.83,
            },
        }]),
        UploadToolServices(),
    )

    assert result.ok is True
    assert result.evidence[0].fact_types == ["file_analysis"]
    assert result.data["file_key"] == file_key
    comparison = result.data["aggregate_comparison"]
    assert comparison["row_level_comparison_available"] is False
    assert comparison["matched_count"] == 0
    assert comparison["different_count"] == 3
    assert comparison["cause_analysis_available"] is False
    assert comparison["confirmed_causes"] == []
    assert comparison["required_fields_for_cause_analysis"] == [
        "admission_id",
        "admit_time",
        "transfer_time",
        "from_dept_id",
        "to_dept_id",
    ]
    assert [item["difference"] for item in comparison["metrics"]] == [133.0, 19.0, 2.92]


def test_system_detail_export_is_parsed_from_real_header_row(tmp_path) -> None:
    path = tmp_path / "hospital_001_MQSI2025_005_20260101_20260801_EXP_x.xlsx"
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "统计范围_2"
    for row in (
        ["指标名称", "急会诊及时到位率"],
        ["指标编号", "MQSI2025_005"],
        ["适用医院", "hospital_001"],
        ["统计区间", "2026-01-01 至 2026-08-01"],
        [],
        ["患者标识", "申请时间", "是否达到要求"],
        ["P001", "2026-01-01 10:00:00", "是"],
        ["P002", "2026-01-02 10:00:00", "否"],
    ):
        sheet.append(row)
    workbook.save(path)

    preview = parse_excel_preview(path)

    assert preview["total_rows"] == 2
    assert preview["detail_export"]["rule_id"] == "MQSI2025_005"
    assert preview["detail_export"]["rule_name"] == "急会诊及时到位率"
    assert preview["sheets"][0]["headers"] == ["患者标识", "申请时间", "是否达到要求"]
    assert preview["indicator_hints"].get("potential_indicator_values") is None


def test_row_level_comparison_separates_both_and_each_side() -> None:
    preview = {
        "detail_export": {
            "rule_id": "MQSI2025_001",
            "rule_name": "患者入院48小时内转科的比例",
            "stat_period": "2026-01-01 至 2026-03-31",
        },
        "_detail_dataset": {
            "headers": ["患者标识", "入院时间", "转科时间", "是否达到要求"],
            "rows": [
                {"患者标识": "P001", "入院时间": "2026-01-01", "转科时间": "2026-01-02", "是否达到要求": "是"},
                {"患者标识": "P003", "入院时间": "2026-01-03", "转科时间": "2026-01-04", "是否达到要求": "否"},
            ],
        },
    }
    system = {
        "rule_id": "MQSI2025_001",
        "rule_name": "患者入院48小时内转科的比例",
        "stat_period": "2026-01-01 至 2026-03-31",
        "columns": [
            {"field": "patient_id", "label": "患者标识"},
            {"field": "admit_time", "label": "入院时间"},
            {"field": "transfer_time", "label": "转科时间"},
        ],
        "rows": [
            {"patient_id": "P001", "admit_time": "2026-01-01", "transfer_time": "2026-01-02", "__meets_numerator": 1},
            {"patient_id": "P002", "admit_time": "2026-01-02", "transfer_time": "2026-01-03", "__meets_numerator": 0},
        ],
    }

    comparison = build_row_level_comparison(preview, system, include_rows=True)

    assert comparison["comparison_status"] == "row_level_compared"
    assert comparison["matching_fields"] == ["患者标识", "入院时间", "转科时间"]
    assert comparison["both_count"] == 1
    assert comparison["system_only_count"] == 1
    assert comparison["uploaded_only_count"] == 1
    assert comparison["matched_rows"][0]["key"].startswith("P001")


def test_row_level_comparison_rejects_different_indicators() -> None:
    comparison = build_row_level_comparison(
        {
            "detail_export": {
                "rule_id": "MQSI2025_005",
                "rule_name": "急会诊及时到位率",
            }
        },
        {
            "rule_id": "MQSI2025_001",
            "rule_name": "患者入院48小时内转科的比例",
        },
    )

    assert comparison["comparison_status"] == "indicator_mismatch"
    assert comparison["row_level_comparison_available"] is False
    assert "两个指标不能" in comparison["message"]


def test_upload_tool_uses_detail_loader_for_same_indicator(tmp_path, monkeypatch) -> None:
    file_key = "hospital_001_MQSI2025_001_detail.xlsx"
    (tmp_path / file_key).write_bytes(b"test")
    monkeypatch.setattr(upload_tools, "_UPLOAD_ROOT", tmp_path)
    monkeypatch.setattr(
        upload_tools,
        "parse_excel_preview",
        lambda _path: {
            "file_name": file_key,
            "sheet_count": 1,
            "total_rows": 1,
            "sheets": [{
                "sheet_name": "统计范围_1",
                "headers": ["患者标识", "入院时间", "是否达到要求"],
                "row_count": 1,
            }],
            "indicator_hints": {},
            "detail_export": {
                "rule_id": "MQSI2025_001",
                "rule_name": "患者入院48小时内转科的比例",
                "stat_period": "2026-01-01 至 2026-03-31",
            },
            "_detail_dataset": {
                "headers": ["患者标识", "入院时间", "是否达到要求"],
                "rows": [{"患者标识": "P001", "入院时间": "2026-01-01", "是否达到要求": "是"}],
            },
        },
    )
    context = AgentRuntimeContext(
        user_id="u1",
        hospital_id="hospital_001",
        session_id="s1",
        user_role="implementer",
        permissions=frozenset({"indicator_detail_view"}),
        request_id="req1",
        trace_id="trace1",
    )
    state = AgentRunState(last_tool_results=[{
        "ok": True,
        "code": "TRIAL_RUN_COMPLETED",
        "data": {
            "run_id": "RUN_001",
            "rule_id": "MQSI2025_001",
            "stat_start": "2026-01-01",
            "stat_end": "2026-03-31",
            "numerator_count": 1,
            "denominator_count": 1,
            "result_value": 100,
        },
    }])
    services = UploadToolServices(detail_loader=lambda *_args: {
        "rule_id": "MQSI2025_001",
        "rule_name": "患者入院48小时内转科的比例",
        "stat_period": "2026-01-01 至 2026-03-31",
        "columns": [
            {"field": "patient_id", "label": "患者标识"},
            {"field": "admit_time", "label": "入院时间"},
        ],
        "rows": [{"patient_id": "P001", "admit_time": "2026-01-01", "__meets_numerator": 1}],
    })

    result = analyze_uploaded_indicators(
        AnalyzeUploadedIndicatorsInput(file_key=file_key),
        context,
        state,
        services,
    )

    assert result.data["row_comparison"]["comparison_status"] == "row_level_compared"
    assert result.data["row_comparison"]["both_count"] == 1
    assert "_detail_dataset" not in result.data

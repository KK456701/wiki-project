from __future__ import annotations

from datetime import datetime
from pathlib import Path

import pytest

openpyxl = pytest.importorskip("openpyxl")

from app.indicator_details.exporter import (  # noqa: E402
    create_indicator_workbook,
    create_upload_comparison_workbook,
    safe_excel_value,
)
from app.indicator_details.models import (  # noqa: E402
    DetailColumn,
    DetailFieldLineage,
    DetailSnapshotSummary,
)


def _summary() -> DetailSnapshotSummary:
    return DetailSnapshotSummary(
        snapshot_id="SNAP_001",
        run_id="RUN_001",
        hospital_id="hospital_001",
        rule_id="MQSI2025_005",
        rule_name="急会诊及时到位率",
        effective_level="hospital",
        national_version="2025",
        hospital_version=1,
        stat_start="2026-07-01 00:00:00",
        stat_end="2026-08-01 00:00:00",
        denominator_count=3,
        numerator_count=2,
        unmatched_count=1,
        columns=[
            DetailColumn(field="patient_id", label="患者标识", sensitivity="patient_id"),
            DetailColumn(field="dept_id", label="科室", sensitivity="none"),
        ],
        created_at=datetime(2026, 7, 14, 9, 0, 0),
        expires_at=datetime(2026, 7, 15, 9, 0, 0),
        source_database="hospital_demo_data",
        source_tables=["consult_record"],
        field_lineage=[
            DetailFieldLineage(
                field="patient_id",
                label="患者标识",
                kind="column",
                sources=["consult_record.patient_id"],
                explanation="来自 consult_record.patient_id",
            ),
            DetailFieldLineage(
                field="dept_id",
                label="科室",
                kind="column",
                sources=["consult_record.dept_id"],
                explanation="来自 consult_record.dept_id",
            ),
            DetailFieldLineage(
                field="arrive_minutes",
                label="到位耗时（分钟）",
                kind="derived",
                sources=[
                    "consult_record.request_time",
                    "consult_record.arrive_time",
                ],
                explanation="由申请时间、到位时间计算",
            ),
        ],
    )


def _rows() -> list[dict]:
    return [
        {"patient_id": "PATIENT001", "dept_id": "急诊科", "__meets_numerator": 1},
        {"patient_id": "PATIENT002", "dept_id": "急诊科", "__meets_numerator": 1},
        {"patient_id": "=2+2", "dept_id": "急诊科", "__meets_numerator": 0},
    ]


def test_excel_contains_three_counted_sheets_and_run_metadata(tmp_path: Path) -> None:
    path = tmp_path / "detail.xlsx"

    create_indicator_workbook(path, _summary(), _rows(), actor_id="user_001")

    workbook = openpyxl.load_workbook(path, read_only=False, data_only=False)
    assert workbook.sheetnames == ["统计范围_3", "达到要求_2", "未达到要求_1"]
    for sheet in workbook.worksheets:
        metadata = {
            sheet.cell(row, 1).value: sheet.cell(row, 2).value
            for row in range(1, 12)
        }
        assert metadata["来源数据库"] == "hospital_demo_data"
        assert metadata["取数表"] == "consult_record"
        assert "患者标识 → consult_record.patient_id" in metadata["字段来源"]
        assert "到位耗时（分钟） → 由申请时间、到位时间计算" in metadata["字段来源"]

    scope = workbook["统计范围_3"]
    assert scope["A1"].value == "指标名称"
    assert scope["B1"].value == "急会诊及时到位率"
    assert scope["A3"].value == "口径来源与版本"
    assert "本院口径 v1" in scope["B3"].value
    assert scope["A13"].value == "患者标识"
    assert scope["A14"].value == "PATIENT001"
    assert scope["A16"].value == "'=2+2"
    assert scope.freeze_panes == "A14"


def test_excel_rejects_count_mismatch_and_formula_values_are_escaped(tmp_path: Path) -> None:
    assert safe_excel_value("@external") == "'@external"
    assert safe_excel_value("普通文本") == "普通文本"

    with pytest.raises(ValueError, match="数量不一致"):
        create_indicator_workbook(tmp_path / "bad.xlsx", _summary(), _rows()[:2], actor_id="user_001")


def test_upload_comparison_excel_separates_matching_and_different_metrics(
    tmp_path: Path,
) -> None:
    path = tmp_path / "comparison.xlsx"
    comparison = {
        "rule_id": "MQSI2025_001",
        "rule_name": "患者入院48小时内转科的比例",
        "hospital_id": "hospital_001",
        "system_stat_period": "2026-01-01 00:00:00 至 2026-07-17 00:00:00",
        "file_name": "hospital_001_report.xlsx",
        "comparison_direction": "上传文件值 - 系统值",
        "row_level_note": "上传文件仅包含汇总值，不能判断具体记录的交集与差集。",
        "matched_count": 1,
        "different_count": 2,
        "metrics": [
            {
                "metric": "分母", "system_value": 389, "uploaded_value": 522,
                "difference": 133, "unit": "人次", "match": False,
                "source_column": "denominator",
            },
            {
                "metric": "分子", "system_value": 11, "uploaded_value": 30,
                "difference": 19, "unit": "人次", "match": False,
                "source_column": "numerator",
            },
            {
                "metric": "指标率", "system_value": 5.75, "uploaded_value": 5.75,
                "difference": 0, "unit": "百分点", "match": True,
                "source_column": "rate_pct",
            },
        ],
    }

    create_upload_comparison_workbook(
        path,
        comparison,
        actor_id="user_001",
        created_at=datetime(2026, 7, 17, 10, 0, 0),
    )

    workbook = openpyxl.load_workbook(path, read_only=False, data_only=False)
    assert workbook.sheetnames == ["对比摘要", "一致项_1", "不一致项_2"]
    summary = workbook["对比摘要"]
    assert summary["B6"].value == "汇总级"
    assert "不能判断具体记录" in summary["B8"].value
    assert summary["D15"].value == 133
    assert workbook["一致项_1"]["A4"].value == "指标率"
    assert workbook["不一致项_2"]["A4"].value == "分母"

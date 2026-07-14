from __future__ import annotations

from pathlib import Path
from typing import Any

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill

from .models import DetailSnapshotSummary


GROUPS = (
    ("denominator", "统计范围", "本次指标计算纳入的全部记录"),
    ("numerator", "达到要求", "在本院口径规定时间或条件内达到要求的记录"),
    ("unmatched", "未达到要求", "已纳入统计范围、但未达到本院口径要求的记录"),
)


def safe_excel_value(value: Any) -> Any:
    if isinstance(value, str) and value.startswith(("=", "+", "-", "@")):
        return "'" + value
    return value


def _group_rows(rows: list[dict[str, Any]], group: str) -> list[dict[str, Any]]:
    if group == "denominator":
        return rows
    expected = 1 if group == "numerator" else 0
    return [
        row
        for row in rows
        if int(row.get("__meets_numerator") or 0) == expected
    ]


def _version_text(summary: DetailSnapshotSummary) -> str:
    if summary.effective_level == "hospital" and summary.hospital_version is not None:
        standard = f"；标准版本 v{summary.national_version}" if summary.national_version else ""
        return f"本院口径 v{summary.hospital_version}{standard}"
    return f"标准口径 v{summary.national_version or '-'}"


def _field_lineage_text(summary: DetailSnapshotSummary) -> str:
    lines: list[str] = []
    for item in summary.field_lineage:
        if item.kind == "column" and item.sources:
            source = item.sources[0]
            lines.append(f"{item.label} → {source}")
        else:
            lines.append(f"{item.label} → {item.explanation}")
    return "\n".join(lines) or "未记录"


def _write_sheet(
    workbook: Workbook,
    *,
    title: str,
    description: str,
    summary: DetailSnapshotSummary,
    rows: list[dict[str, Any]],
    actor_id: str,
    first: bool,
) -> None:
    sheet = workbook.active if first else workbook.create_sheet()
    sheet.title = f"{title}_{len(rows)}"
    metadata = (
        ("指标名称", summary.rule_name),
        ("适用医院", summary.hospital_id),
        ("口径来源与版本", _version_text(summary)),
        ("来源数据库", summary.source_database or "未记录"),
        ("取数表", "、".join(summary.source_tables) or "未记录"),
        ("字段来源", _field_lineage_text(summary)),
        ("统计区间", f"{summary.stat_start} 至 {summary.stat_end}（不含结束时刻）"),
        ("明细快照时间", summary.created_at.isoformat(sep=" ", timespec="seconds")),
        ("导出人", actor_id),
        ("本表说明", description),
        ("记录总数", len(rows)),
    )
    for index, (label, value) in enumerate(metadata, start=1):
        sheet.cell(index, 1, label)
        sheet.cell(index, 2, safe_excel_value(value))
        sheet.cell(index, 1).font = Font(bold=True)
        if label == "字段来源":
            sheet.cell(index, 2).alignment = Alignment(
                vertical="top", wrap_text=True
            )
            sheet.row_dimensions[index].height = max(
                30, 15 * max(1, len(summary.field_lineage))
            )

    labels = [column.label for column in summary.columns] + ["是否达到要求"]
    header_row = len(metadata) + 2
    data_start_row = header_row + 1
    for column_index, label in enumerate(labels, start=1):
        cell = sheet.cell(header_row, column_index, label)
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="087F78")
        cell.alignment = Alignment(horizontal="center")
    for row_index, row in enumerate(rows, start=data_start_row):
        values = [row.get(column.field) for column in summary.columns]
        values.append("是" if int(row.get("__meets_numerator") or 0) == 1 else "否")
        for column_index, value in enumerate(values, start=1):
            sheet.cell(row_index, column_index, safe_excel_value(value))
    sheet.freeze_panes = f"A{data_start_row}"
    last_row = max(header_row, data_start_row + len(rows) - 1)
    sheet.auto_filter.ref = (
        f"A{header_row}:{sheet.cell(last_row, len(labels)).coordinate}"
    )
    for index, label in enumerate(labels, start=1):
        sheet.column_dimensions[sheet.cell(header_row, index).column_letter].width = min(
            36, max(14, len(label) * 2 + 4)
        )
    sheet.column_dimensions["B"].width = max(
        float(sheet.column_dimensions["B"].width or 0), 36
    )


def create_indicator_workbook(
    path: Path,
    summary: DetailSnapshotSummary,
    rows: list[dict[str, Any]],
    *,
    actor_id: str,
) -> Path:
    numerator_count = sum(
        1 for row in rows if int(row.get("__meets_numerator") or 0) == 1
    )
    if (
        len(rows) != summary.denominator_count
        or numerator_count != summary.numerator_count
        or len(rows) - numerator_count != summary.unmatched_count
    ):
        raise ValueError("快照明细与聚合数量不一致，不能生成 Excel")
    workbook = Workbook()
    for index, (group, title, description) in enumerate(GROUPS):
        _write_sheet(
            workbook,
            title=title,
            description=description,
            summary=summary,
            rows=_group_rows(rows, group),
            actor_id=actor_id,
            first=index == 0,
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    workbook.save(path)
    return path

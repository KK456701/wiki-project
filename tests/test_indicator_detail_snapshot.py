from __future__ import annotations

import gzip
import importlib
import importlib.util
import json
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.db_access.query_result import QueryResult
from tests.test_indicator_detail_sql import make_context


class MutableClock:
    def __init__(self) -> None:
        self.value = datetime(2026, 7, 14, 10, 0, 0)

    def __call__(self) -> datetime:
        return self.value

    def advance(self, **kwargs: int) -> None:
        self.value += timedelta(**kwargs)


class FakeBusinessDB:
    source_id = "hospital_demo_data"
    tool_name = "execute_sql_hospital_demo_data"

    def __init__(self, rows: list[dict]) -> None:
        self.rows = rows
        self.calls: list[str] = []

    def execute_select(self, sql: str) -> QueryResult:
        self.calls.append(sql)
        return QueryResult(
            rows=[dict(row) for row in self.rows],
            row_count=len(self.rows),
            source=self.source_id,
            tool_name=self.tool_name,
            duration_ms=3,
        )


def _modules():
    assert importlib.util.find_spec("app.indicator_details.snapshot") is not None, (
        "指标明细短期快照尚未实现"
    )
    return (
        importlib.import_module("app.indicator_details.repository"),
        importlib.import_module("app.indicator_details.schema"),
        importlib.import_module("app.indicator_details.snapshot"),
    )


def _runtime_engine():
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_sql_run_log (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id TEXT NOT NULL UNIQUE,
                  sql_id TEXT,
                  hospital_id TEXT NOT NULL,
                  rule_id TEXT NOT NULL,
                  stat_start_time TEXT,
                  stat_end_time TEXT,
                  run_status TEXT NOT NULL,
                  result_value REAL,
                  error_message TEXT,
                  duration_ms INTEGER,
                  run_by TEXT,
                  numerator_count INTEGER,
                  denominator_count INTEGER,
                  run_context_json TEXT,
                  run_time TEXT NOT NULL
                )
                """
            )
        )
    return engine


def _store(tmp_path: Path, rows: list[dict], *, numerator: int = 2, denominator: int = 3):
    repository_module, schema_module, snapshot_module = _modules()
    engine = _runtime_engine()
    schema_module.ensure_indicator_detail_schema(engine)
    context = make_context("MQSI2025_005")
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_sql_run_log
                  (run_id, sql_id, hospital_id, rule_id, stat_start_time,
                   stat_end_time, run_status, result_value, numerator_count,
                   denominator_count, run_context_json, run_time)
                VALUES
                  ('RUN_DETAIL_001', 'SQL_DETAIL_001', 'hospital_001',
                   'MQSI2025_005', :start_time, :end_time, 'success', 66.67,
                   :numerator, :denominator, :context, :run_time)
                """
            ),
            {
                "start_time": context.stat_start,
                "end_time": context.stat_end,
                "numerator": numerator,
                "denominator": denominator,
                "context": context.model_dump_json(),
                "run_time": "2026-07-14 09:59:00",
            },
        )
    clock = MutableClock()
    repository = repository_module.IndicatorDetailRepository(engine)
    business_db = FakeBusinessDB(rows)
    store = snapshot_module.DetailSnapshotStore(
        repository,
        business_db,
        export_root=tmp_path / "runtime" / "exports",
        now_provider=clock,
    )
    return store, repository, business_db, clock


def _rows() -> list[dict]:
    return [
        {
            "patient_id": "PATIENT0001",
            "dept_id": "ED",
            "consult_type": "急会诊",
            "request_time": "2026-07-03 09:00:00",
            "arrive_time": "2026-07-03 09:08:00",
            "arrive_minutes": 8,
            "__meets_numerator": 1,
            "__evidence_row_count": 1,
        },
        {
            "patient_id": "PATIENT0002",
            "dept_id": "ICU",
            "consult_type": "急会诊",
            "request_time": "2026-07-03 10:00:00",
            "arrive_time": "2026-07-03 10:18:00",
            "arrive_minutes": 18,
            "__meets_numerator": 1,
            "__evidence_row_count": 1,
        },
        {
            "patient_id": "PATIENT0003",
            "dept_id": "WARD",
            "consult_type": "急会诊",
            "request_time": "2026-07-03 11:00:00",
            "arrive_time": "2026-07-03 11:31:00",
            "arrive_minutes": 31,
            "__meets_numerator": 0,
            "__evidence_row_count": 1,
        },
    ]


def test_snapshot_counts_match_run_and_preview_is_masked(tmp_path: Path) -> None:
    store, repository, business_db, _ = _store(tmp_path, _rows())

    summary = store.create("RUN_DETAIL_001", "hospital_001", "user_001")
    numerator_page = store.read_page(
        "RUN_DETAIL_001", "hospital_001", "numerator", page=1, page_size=50
    )

    assert summary.denominator_count == 3
    assert summary.numerator_count == 2
    assert summary.unmatched_count == 1
    assert numerator_page.total == 2
    assert numerator_page.items[0]["患者标识"] == "PA*******01"
    assert "PATIENT0001" not in repr(numerator_page.items)
    assert len(business_db.calls) == 1
    snapshot = repository.get_snapshot_by_run("RUN_DETAIL_001")
    path = store.resolve_snapshot_path(snapshot)
    assert path.suffix == ".gz"
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        first = json.loads(handle.readline())
    assert first["__meta__"]["run_id"] == "RUN_DETAIL_001"


def test_snapshot_is_reused_without_querying_business_db_again(tmp_path: Path) -> None:
    store, _, business_db, _ = _store(tmp_path, _rows())

    first = store.create("RUN_DETAIL_001", "hospital_001", "user_001")
    second = store.create("RUN_DETAIL_001", "hospital_001", "user_001")

    assert first.snapshot_id == second.snapshot_id
    assert len(business_db.calls) == 1


def test_changed_business_data_blocks_snapshot(tmp_path: Path) -> None:
    store, repository, business_db, _ = _store(tmp_path, _rows()[:2])

    with pytest.raises(ValueError, match="业务数据已经变化"):
        store.create("RUN_DETAIL_001", "hospital_001", "user_001")

    snapshot = repository.get_snapshot_by_run("RUN_DETAIL_001")
    assert snapshot["status"] == "failed"

    business_db.rows = _rows()
    summary = store.create("RUN_DETAIL_001", "hospital_001", "user_001")
    snapshot = repository.get_snapshot_by_run("RUN_DETAIL_001")

    assert snapshot is not None
    assert summary.snapshot_id == snapshot["snapshot_id"]
    assert Path(snapshot["relative_path"]).name == f"{summary.snapshot_id}.jsonl.gz"


def test_more_than_twenty_thousand_rows_is_rejected(tmp_path: Path) -> None:
    rows = [
        {"patient_id": f"P{index:05d}", "__meets_numerator": 0, "__evidence_row_count": 1}
        for index in range(20_001)
    ]
    store, _, _, _ = _store(tmp_path, rows, numerator=0, denominator=20_001)

    with pytest.raises(ValueError, match="明细超过20,000条"):
        store.create("RUN_DETAIL_001", "hospital_001", "user_001")


def test_expired_or_tampered_snapshot_cannot_be_previewed(tmp_path: Path) -> None:
    store, repository, _, clock = _store(tmp_path, _rows())
    store.create("RUN_DETAIL_001", "hospital_001", "user_001")
    snapshot = repository.get_snapshot_by_run("RUN_DETAIL_001")
    path = store.resolve_snapshot_path(snapshot)
    path.write_bytes(path.read_bytes() + b"tampered")

    with pytest.raises(ValueError, match="文件校验失败"):
        store.read_page("RUN_DETAIL_001", "hospital_001", "denominator", 1, 50)

    path.write_bytes(path.read_bytes()[:-8])
    clock.advance(hours=24, seconds=1)
    with pytest.raises(ValueError, match="明细已过期"):
        store.read_page("RUN_DETAIL_001", "hospital_001", "denominator", 1, 50)


def test_snapshot_path_cannot_escape_export_root(tmp_path: Path) -> None:
    store, repository, _, _ = _store(tmp_path, _rows())
    store.create("RUN_DETAIL_001", "hospital_001", "user_001")
    with repository.engine.begin() as conn:
        conn.execute(
            text(
                "UPDATE med_indicator_detail_snapshot "
                "SET relative_path='../../outside.jsonl.gz' WHERE run_id='RUN_DETAIL_001'"
            )
        )

    with pytest.raises(ValueError, match="快照路径无效"):
        store.read_page("RUN_DETAIL_001", "hospital_001", "denominator", 1, 50)

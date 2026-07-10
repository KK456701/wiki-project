import json
import unittest
from datetime import datetime, timedelta
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool


class RuntimeRuleSchemaTest(unittest.TestCase):
    def test_runtime_schema_contains_rule_store_tables(self) -> None:
        ddl = Path("scripts/init_runtime_db.sql").read_text(encoding="utf-8")
        for table in (
            "med_index_standard",
            "med_index_hospital_custom",
            "med_index_hospital_custom_version",
        ):
            self.assertIn(f"CREATE TABLE IF NOT EXISTS {table}", ddl)
        for column in (
            "standard_sql LONGTEXT",
            "rule_params JSON",
            "custom_params JSON",
            "approval_status VARCHAR(32)",
            "effective_from DATETIME",
            "effective_to DATETIME",
            "snapshot_json JSON",
        ):
            self.assertIn(column, ddl)


def _rule_engine():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE med_index_standard (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  index_code TEXT NOT NULL UNIQUE,
                  index_name TEXT NOT NULL,
                  index_type TEXT NOT NULL,
                  index_desc TEXT NOT NULL,
                  stat_cycle TEXT NOT NULL,
                  numerator_rule TEXT NOT NULL,
                  denominator_rule TEXT NOT NULL,
                  filter_rule TEXT,
                  exclude_rule TEXT,
                  rely_table_field TEXT NOT NULL,
                  standard_sql TEXT NOT NULL,
                  rule_params TEXT NOT NULL,
                  source_path TEXT,
                  version TEXT NOT NULL,
                  status INTEGER NOT NULL,
                  create_time TEXT NOT NULL,
                  update_time TEXT NOT NULL
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE med_index_hospital_custom (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  hospital_id TEXT NOT NULL,
                  index_code TEXT NOT NULL,
                  custom_numerator TEXT,
                  custom_denominator TEXT,
                  custom_filter TEXT,
                  exclude_rule TEXT,
                  custom_params TEXT NOT NULL,
                  custom_sql TEXT,
                  version INTEGER NOT NULL,
                  status INTEGER NOT NULL,
                  approval_status TEXT NOT NULL,
                  effective_from TEXT,
                  effective_to TEXT,
                  oper_user TEXT,
                  create_time TEXT NOT NULL,
                  update_time TEXT NOT NULL,
                  UNIQUE (hospital_id, index_code)
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE med_field_mapping (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  hospital_id TEXT NOT NULL,
                  rule_id TEXT NOT NULL,
                  business_field TEXT NOT NULL,
                  db_name TEXT NOT NULL,
                  table_name TEXT NOT NULL,
                  column_name TEXT NOT NULL,
                  data_type TEXT,
                  status TEXT NOT NULL,
                  updated_by TEXT,
                  updated_at TEXT NOT NULL,
                  UNIQUE (hospital_id, rule_id, business_field)
                )
                """
            )
        )
        conn.execute(
            text(
                """
                CREATE TABLE med_index_hospital_custom_version (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  change_id TEXT NOT NULL UNIQUE,
                  hospital_id TEXT NOT NULL,
                  index_code TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  approval_status TEXT NOT NULL,
                  snapshot_json TEXT NOT NULL,
                  source_version INTEGER,
                  change_type TEXT NOT NULL,
                  oper_user TEXT,
                  approver_id TEXT,
                  created_at TEXT NOT NULL,
                  approved_at TEXT,
                  UNIQUE (hospital_id, index_code, version)
                )
                """
            )
        )
    return engine


def _seed_standard(engine) -> None:
    now = datetime.now().isoformat(sep=" ", timespec="seconds")
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_index_standard
                  (index_code, index_name, index_type, index_desc, stat_cycle,
                   numerator_rule, denominator_rule, filter_rule, exclude_rule,
                   rely_table_field, standard_sql, rule_params, source_path,
                   version, status, create_time, update_time)
                VALUES
                  (:code, :name, '会诊制度', :description, 'month',
                   :numerator, :denominator, '', '', :fields, :sql, :params,
                   'wiki/standards/national/MQSI2025_005.md', '2025', 1, :now, :now)
                """
            ),
            {
                "code": "MQSI2025_005",
                "name": "急会诊及时到位率",
                "description": "急会诊请求发出后及时到位的比例。",
                "numerator": "10分钟内到位急会诊次数",
                "denominator": "同期急会诊总次数",
                "fields": json.dumps({"request_time": {"type": "datetime"}}, ensure_ascii=False),
                "sql": "SELECT 1 AS index_value, 1 AS sample_count",
                "params": json.dumps({"arrive_minutes_threshold": 10}, ensure_ascii=False),
                "now": now,
            },
        )


def _seed_custom(
    engine,
    *,
    status: int = 1,
    approval_status: str = "approved",
    effective_from: str | None = None,
    effective_to: str | None = None,
) -> None:
    now = datetime.now().isoformat(sep=" ", timespec="seconds")
    with engine.begin() as conn:
        conn.execute(
            text(
                """
                INSERT INTO med_index_hospital_custom
                  (hospital_id, index_code, custom_numerator, custom_denominator,
                   custom_filter, exclude_rule, custom_params, custom_sql,
                   version, status, approval_status, effective_from, effective_to,
                   oper_user, create_time, update_time)
                VALUES
                  ('hospital_001', 'MQSI2025_005', :numerator, NULL, NULL, NULL,
                   :params, NULL, 1, :status, :approval_status, :effective_from,
                   :effective_to, 'admin', :now, :now)
                """
            ),
            {
                "numerator": "20分钟内到位急会诊次数",
                "params": json.dumps({"arrive_minutes_threshold": 20}, ensure_ascii=False),
                "status": status,
                "approval_status": approval_status,
                "effective_from": effective_from,
                "effective_to": effective_to,
                "now": now,
            },
        )


class MySQLRuleRepositoryTest(unittest.TestCase):
    def test_approved_hospital_rule_overrides_national_params(self) -> None:
        from app.rules.repository import MySQLRuleRepository

        engine = _rule_engine()
        _seed_standard(engine)
        _seed_custom(engine)

        result = MySQLRuleRepository(engine).get_effective_rule(
            "MQSI2025_005", "hospital_001"
        )

        self.assertEqual(result["effective_level"], "hospital")
        self.assertEqual(result["rule_source"], "mysql")
        self.assertEqual(result["national_params"]["arrive_minutes_threshold"], 10)
        self.assertEqual(result["effective_params"]["arrive_minutes_threshold"], 20)
        self.assertEqual(result["fallback_chain"], ["hospital", "national"])

    def test_inactive_hospital_rule_falls_back_to_national(self) -> None:
        from app.rules.repository import MySQLRuleRepository

        cases = [
            {"status": 0},
            {"approval_status": "pending"},
            {"effective_from": (datetime.now() + timedelta(days=1)).isoformat(sep=" ")},
            {"effective_to": (datetime.now() - timedelta(days=1)).isoformat(sep=" ")},
        ]
        for custom_options in cases:
            with self.subTest(custom_options=custom_options):
                engine = _rule_engine()
                _seed_standard(engine)
                _seed_custom(engine, **custom_options)

                result = MySQLRuleRepository(engine).get_effective_rule(
                    "急会诊及时到位率", "hospital_001"
                )

                self.assertEqual(result["effective_level"], "national")
                self.assertEqual(result["effective_params"]["arrive_minutes_threshold"], 10)


if __name__ == "__main__":
    unittest.main()

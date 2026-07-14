from __future__ import annotations

import io
import json
import zipfile
from datetime import datetime
from pathlib import Path

import pytest
import yaml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.kb.export import export_hospital_kb_zip
from app.kb.scope import MetadataExportScopeError, MetadataExportScopeRepository
from app.kb.signing import PackageSigner, verify_checksums


def _engine():
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    statements = [
        """CREATE TABLE med_metadata_table (
          hospital_id TEXT, db_name TEXT, table_name TEXT, table_comment TEXT,
          table_type TEXT, sync_batch_id TEXT, sync_time DATETIME)""",
        """CREATE TABLE med_metadata_column (
          hospital_id TEXT, db_name TEXT, table_name TEXT, column_name TEXT,
          data_type TEXT, column_type TEXT, is_nullable TEXT, column_key TEXT,
          column_default TEXT, column_comment TEXT, sync_batch_id TEXT,
          sync_time DATETIME)""",
        """CREATE TABLE med_table_relation (
          hospital_id TEXT, db_name TEXT, left_table TEXT, left_column TEXT,
          right_table TEXT, right_column TEXT, join_type TEXT,
          relation_source TEXT, status TEXT, updated_by TEXT, updated_at DATETIME)""",
        """CREATE TABLE med_index_standard (
          index_code TEXT PRIMARY KEY, index_name TEXT, index_type TEXT,
          index_desc TEXT, numerator_rule TEXT, denominator_rule TEXT,
          filter_rule TEXT, exclude_rule TEXT, version TEXT, status INTEGER)""",
        """CREATE TABLE med_index_hospital_custom (
          hospital_id TEXT, index_code TEXT, custom_numerator TEXT,
          custom_denominator TEXT, custom_filter TEXT, exclude_rule TEXT,
          custom_params TEXT, custom_sql TEXT, version INTEGER, status INTEGER,
          approval_status TEXT, effective_from DATETIME, effective_to DATETIME,
          oper_user TEXT, update_time DATETIME)""",
        """CREATE TABLE med_field_mapping (
          hospital_id TEXT, rule_id TEXT, business_field TEXT, db_name TEXT,
          table_name TEXT, column_name TEXT, data_type TEXT, status TEXT,
          updated_by TEXT, updated_at DATETIME)""",
        """CREATE TABLE med_sql_run_log (
          run_id TEXT, sql_id TEXT, hospital_id TEXT, rule_id TEXT,
          stat_start_time DATETIME, stat_end_time DATETIME, run_status TEXT,
          result_value REAL, error_message TEXT, duration_ms INTEGER,
          run_by TEXT, numerator_count INTEGER, denominator_count INTEGER,
          run_context_json TEXT, run_time DATETIME)""",
    ]
    with engine.begin() as conn:
        for statement in statements:
            conn.execute(text(statement))
        now = datetime(2026, 7, 14, 12, 0, 0)
        conn.execute(
            text(
                """INSERT INTO med_metadata_table VALUES
                ('hospital_001','hospital_demo_data','consult_record','会诊记录','BASE TABLE','b1',:now),
                ('hospital_001','hospital_demo_data','patient_secret','患者敏感表','BASE TABLE','b1',:now)"""
            ),
            {"now": now},
        )
        conn.execute(
            text(
                """INSERT INTO med_metadata_column VALUES
                ('hospital_001','hospital_demo_data','consult_record','request_time','datetime','datetime','NO','','2026-01-01','申请时间','b1',:now),
                ('hospital_001','hospital_demo_data','consult_record','arrive_time','datetime','datetime','YES','','','到位时间','b1',:now),
                ('hospital_001','hospital_demo_data','patient_secret','patient_name','varchar','varchar(50)','NO','','张三','患者姓名','b1',:now)"""
            ),
            {"now": now},
        )
        conn.execute(
            text(
                """INSERT INTO med_index_standard VALUES
                ('R001','急会诊及时到位率','会诊制度','标准定义','标准分子','标准分母',NULL,NULL,'2025',1)"""
            )
        )
        conn.execute(
            text(
                """INSERT INTO med_index_hospital_custom VALUES
                ('hospital_001','R001','本院20分钟内到位次数',NULL,NULL,NULL,
                 :params,'SELECT patient_name FROM patient_secret',1,1,'approved',
                 NULL,NULL,'tester',:now)"""
            ),
            {"params": json.dumps({"arrive_minutes_threshold": 20}), "now": now},
        )
        conn.execute(
            text(
                """INSERT INTO med_field_mapping VALUES
                ('hospital_001','R001','request_time','hospital_demo_data','consult_record','request_time','datetime','confirmed','tester',:now),
                ('hospital_001','R001','patient_name','hospital_demo_data','patient_secret','patient_name','varchar','confirmed','tester',:now)"""
            ),
            {"now": now},
        )
        conn.execute(
            text(
                """INSERT INTO med_sql_run_log VALUES
                ('RUN_001','SQL_001','hospital_001','R001','2026-07-01','2026-08-01',
                 'success',84.72,NULL,18,'tester',488,576,'{}',:now)"""
            ),
            {"now": now},
        )
    return engine


def _signer(tmp_path: Path) -> tuple[PackageSigner, Path]:
    private = Ed25519PrivateKey.generate()
    private_path = tmp_path / "hospital.pem"
    trusted = tmp_path / "trusted"
    trusted.mkdir()
    private_path.write_bytes(
        private.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    (trusted / "hospital_001.pem").write_bytes(
        private.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return PackageSigner.from_private_pem(private_path, "hospital_001"), trusted


def test_scope_rejects_columns_outside_synchronized_metadata() -> None:
    repository = MetadataExportScopeRepository(_engine())

    with pytest.raises(MetadataExportScopeError, match="METADATA_SCOPE_COLUMN_UNKNOWN"):
        repository.replace_scope(
            "hospital_001",
            "hospital_demo_data",
            [{"table_name": "consult_record", "column_name": "missing"}],
            "admin",
        )


def test_scope_preview_lists_selected_and_excluded_content() -> None:
    repository = MetadataExportScopeRepository(_engine())
    repository.replace_scope(
        "hospital_001",
        "hospital_demo_data",
        [{"table_name": "consult_record", "column_name": "request_time"}],
        "admin",
    )

    preview = repository.preview_scope("hospital_001", "hospital_demo_data")

    assert preview["selected_table_count"] == 1
    assert preview["selected_column_count"] == 1
    assert preview["tables"][0]["table_name"] == "consult_record"
    assert "患者数据行" in preview["excluded_content"]


def test_signed_export_contains_only_whitelisted_metadata_and_aggregate_feedback(
    tmp_path: Path,
) -> None:
    engine = _engine()
    repository = MetadataExportScopeRepository(engine)
    repository.replace_scope(
        "hospital_001",
        "hospital_demo_data",
        [
            {"table_name": "consult_record", "column_name": "request_time"},
            {"table_name": "consult_record", "column_name": "arrive_time"},
        ],
        "admin",
    )
    signer, trusted = _signer(tmp_path)

    package = export_hospital_kb_zip(
        engine,
        "hospital_001",
        db_name="hospital_demo_data",
        signer=signer,
        actor_id="admin",
    )

    with zipfile.ZipFile(io.BytesIO(package), "r") as zf:
        manifest = yaml.safe_load(zf.read("manifest.yaml"))
        metadata = yaml.safe_load(zf.read("metadata/hospital_demo_data.yaml"))
        mapping = yaml.safe_load(zf.read("mappings/R001.yaml"))
        override = yaml.safe_load(zf.read("overrides/R001.yaml"))
        validation = yaml.safe_load(zf.read("validation/R001.yaml"))
        checksums = zf.read("checksums.json")
        signature = json.loads(zf.read("signature.json"))

    assert manifest["format_version"] == "kb-exchange-v4"
    assert manifest["metadata_column_count"] == 2
    assert {item["column_name"] for item in metadata["columns"]} == {
        "request_time",
        "arrive_time",
    }
    assert all("column_default" not in item for item in metadata["columns"])
    assert "patient_secret" not in json.dumps(metadata, ensure_ascii=False)
    assert list(mapping["fields"]) == ["request_time"]
    assert "custom_sql" not in override
    assert validation["numerator_count"] == 488
    assert validation["denominator_count"] == 576
    assert "error_message" not in validation
    assert verify_checksums(checksums, signature, trusted)["status"] == "verified"

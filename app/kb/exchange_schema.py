from __future__ import annotations

from sqlalchemy import (
    JSON,
    BigInteger,
    Column,
    DateTime,
    Engine,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    UniqueConstraint,
    inspect,
)


metadata = MetaData()
_id_type = BigInteger().with_variant(Integer, "sqlite")

metadata_export_scope = Table(
    "med_metadata_export_scope",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("hospital_id", String(64), nullable=False),
    Column("db_name", String(128), nullable=False),
    Column("table_name", String(128), nullable=False),
    Column("column_name", String(128), nullable=False),
    Column("selected_by", String(64), nullable=False),
    Column("updated_at", DateTime, nullable=False),
    UniqueConstraint(
        "hospital_id",
        "db_name",
        "table_name",
        "column_name",
        name="uk_metadata_export_scope",
    ),
)

company_package_import = Table(
    "med_company_package_import",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("import_id", String(64), nullable=False, unique=True),
    Column("package_id", String(64), nullable=False, unique=True),
    Column("release_id", String(64)),
    Column("format_version", String(32), nullable=False),
    Column("package_checksum", String(64), nullable=False),
    Column("signer_key_id", String(96)),
    Column("signature_status", String(32), nullable=False),
    Column("compatibility_status", String(32), nullable=False),
    Column("status", String(32), nullable=False),
    Column("manifest_json", JSON, nullable=False),
    Column("compatibility_json", JSON, nullable=False),
    Column("imported_by", String(64), nullable=False),
    Column("imported_at", DateTime, nullable=False),
)

company_package_item = Table(
    "med_company_package_item",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("import_id", String(64), nullable=False),
    Column("item_path", String(512), nullable=False),
    Column("item_type", String(32), nullable=False),
    Column("rule_id", String(64)),
    Column("payload_json", JSON, nullable=False),
    UniqueConstraint("import_id", "item_path", name="uk_company_package_item"),
)

package_audit = Table(
    "med_package_audit",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("direction", String(32), nullable=False),
    Column("package_id", String(64), nullable=False),
    Column("hospital_id", String(64)),
    Column("event_type", String(32), nullable=False),
    Column("status", String(32), nullable=False),
    Column("actor_id", String(64), nullable=False),
    Column("detail_json", JSON, nullable=False),
    Column("created_at", DateTime, nullable=False),
    Column("message", Text),
)

EXCHANGE_TABLES = (
    "med_metadata_export_scope",
    "med_company_package_import",
    "med_company_package_item",
    "med_package_audit",
)


def ensure_kb_exchange_schema(engine: Engine) -> dict[str, list[str]]:
    before = set(inspect(engine).get_table_names())
    metadata.create_all(engine, checkfirst=True)
    after = set(inspect(engine).get_table_names())
    return {
        "created_tables": [name for name in EXCHANGE_TABLES if name in after - before]
    }

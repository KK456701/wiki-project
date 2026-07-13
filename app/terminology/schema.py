"""术语库运行表的幂等建表逻辑。"""

from __future__ import annotations

from sqlalchemy import (
    JSON,
    BigInteger,
    Column,
    DateTime,
    Engine,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    UniqueConstraint,
    inspect,
    text,
)


def _metadata() -> MetaData:
    metadata = MetaData()
    Table(
        "med_term_concept",
        metadata,
        Column("id", BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True),
        Column("concept_code", String(96), nullable=False),
        Column("canonical_name", String(255), nullable=False),
        Column("concept_type", String(32), nullable=False),
        Column("definition", Text, nullable=False, default=""),
        Column("standard_code", String(128)),
        Column("source_level", String(32), nullable=False),
        Column("source_reference", Text, nullable=False, default=""),
        Column("version", Integer, nullable=False),
        Column("status", String(32), nullable=False),
        Column("created_at", DateTime, nullable=False),
        Column("updated_at", DateTime, nullable=False),
        UniqueConstraint("concept_code", name="uk_term_concept_code"),
    )
    Table(
        "med_term_alias",
        metadata,
        Column("id", BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True),
        Column("hospital_id", String(64), nullable=False, default=""),
        Column("concept_code", String(96), nullable=False),
        Column("alias_text", String(255), nullable=False),
        Column("relation_type", String(32), nullable=False),
        Column("retrieval_enabled", Integer, nullable=False, default=1),
        Column("sql_safe", Integer, nullable=False, default=0),
        Column("ambiguity_group", String(96)),
        Column("source_reference", Text, nullable=False, default=""),
        Column("approval_status", String(32), nullable=False),
        Column("version", Integer, nullable=False),
        Column("created_by", String(64)),
        Column("approved_by", String(64)),
        Column("created_at", DateTime, nullable=False),
        Column("approved_at", DateTime),
        UniqueConstraint(
            "hospital_id", "concept_code", "alias_text", "version",
            name="uk_term_alias_scope",
        ),
    )
    Table(
        "med_term_rule_link",
        metadata,
        Column("id", BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True),
        Column("concept_code", String(96), nullable=False),
        Column("index_code", String(64), nullable=False),
        Column("usage_section", String(32), nullable=False),
        Column("business_field_key", String(128)),
        Column("source_reference", Text, nullable=False, default=""),
        Column("version", Integer, nullable=False),
        UniqueConstraint(
            "concept_code", "index_code", "usage_section", "version",
            name="uk_term_rule_link",
        ),
    )
    Table(
        "med_hospital_term_mapping",
        metadata,
        Column("id", BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True),
        Column("hospital_id", String(64), nullable=False),
        Column("concept_code", String(96), nullable=False),
        Column("code_system", String(64), nullable=False),
        Column("local_code", String(128), nullable=False, default=""),
        Column("local_name", String(255), nullable=False),
        Column("local_value", String(255), nullable=False),
        Column("approval_status", String(32), nullable=False),
        Column("effective_from", DateTime),
        Column("effective_to", DateTime),
        Column("version", Integer, nullable=False),
        Column("created_by", String(64)),
        Column("approved_by", String(64)),
        Column("created_at", DateTime, nullable=False),
        Column("approved_at", DateTime),
        UniqueConstraint(
            "hospital_id", "concept_code", "code_system", "local_code", "version",
            name="uk_hospital_term_current",
        ),
    )
    Table(
        "med_hospital_term_mapping_version",
        metadata,
        Column("id", BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True),
        Column("version_id", String(64), nullable=False, unique=True),
        Column("hospital_id", String(64), nullable=False),
        Column("concept_code", String(96), nullable=False),
        Column("version", Integer, nullable=False),
        Column("snapshot_json", JSON, nullable=False),
        Column("change_type", String(64), nullable=False),
        Column("oper_user", String(64)),
        Column("approver_id", String(64)),
        Column("created_at", DateTime, nullable=False),
        Column("approved_at", DateTime),
        UniqueConstraint("hospital_id", "concept_code", "version", name="uk_hospital_term_version"),
    )
    Table(
        "med_term_release",
        metadata,
        Column("id", BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True),
        Column("release_id", String(64), nullable=False, unique=True),
        Column("version", Integer, nullable=False, unique=True),
        Column("status", String(32), nullable=False),
        Column("checksum", String(64), nullable=False, unique=True),
        Column("snapshot_json", JSON, nullable=False),
        Column("change_summary", Text, nullable=False, default=""),
        Column("published_by", String(64), nullable=False),
        Column("published_at", DateTime, nullable=False),
    )
    Table(
        "med_term_audit_log",
        metadata,
        Column("id", BigInteger().with_variant(Integer, "sqlite"), primary_key=True, autoincrement=True),
        Column("action", String(64), nullable=False),
        Column("object_type", String(64), nullable=False),
        Column("object_id", String(128), nullable=False),
        Column("hospital_id", String(64)),
        Column("version", String(64)),
        Column("actor_id", String(64), nullable=False),
        Column("detail_json", JSON, nullable=False),
        Column("created_at", DateTime, nullable=False),
    )
    Index("idx_term_alias_text", metadata.tables["med_term_alias"].c.alias_text)
    Index("idx_term_rule_code", metadata.tables["med_term_rule_link"].c.index_code)
    Index(
        "idx_hospital_term_active",
        metadata.tables["med_hospital_term_mapping"].c.hospital_id,
        metadata.tables["med_hospital_term_mapping"].c.approval_status,
    )
    return metadata


def ensure_terminology_schema(engine: Engine) -> dict[str, list[str]]:
    metadata = _metadata()
    existing = set(inspect(engine).get_table_names())
    created = [name for name in metadata.tables if name not in existing]
    metadata.create_all(engine, checkfirst=True)
    added_columns: list[str] = []
    if "med_term_alias" in existing:
        columns = {item["name"] for item in inspect(engine).get_columns("med_term_alias")}
        if "hospital_id" not in columns:
            with engine.begin() as conn:
                conn.execute(
                    text(
                        "ALTER TABLE med_term_alias ADD COLUMN "
                        "hospital_id VARCHAR(64) NOT NULL DEFAULT ''"
                    )
                )
            added_columns.append("med_term_alias.hospital_id")
        if engine.dialect.name == "mysql":
            constraints = {
                item["name"]: item.get("column_names") or []
                for item in inspect(engine).get_unique_constraints("med_term_alias")
            }
            if constraints.get("uk_term_alias_scope") != [
                "hospital_id", "concept_code", "alias_text", "version"
            ]:
                with engine.begin() as conn:
                    conn.execute(text("ALTER TABLE med_term_alias DROP INDEX uk_term_alias_scope"))
                    conn.execute(
                        text(
                            "ALTER TABLE med_term_alias ADD UNIQUE KEY "
                            "uk_term_alias_scope "
                            "(hospital_id, concept_code, alias_text, version)"
                        )
                    )
    return {"created_tables": created, "added_columns": added_columns}

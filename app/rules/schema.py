"""指标结构化计算定义和表关联关系的幂等迁移。"""

from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    Engine,
    Integer,
    MetaData,
    String,
    Table,
    UniqueConstraint,
    inspect,
    text,
)


metadata = MetaData()
_id_type = BigInteger().with_variant(Integer, "sqlite")

table_relation = Table(
    "med_table_relation",
    metadata,
    Column("id", _id_type, primary_key=True, autoincrement=True),
    Column("hospital_id", String(64), nullable=False),
    Column("db_name", String(128), nullable=False),
    Column("left_table", String(128), nullable=False),
    Column("left_column", String(128), nullable=False),
    Column("right_table", String(128), nullable=False),
    Column("right_column", String(128), nullable=False),
    Column("join_type", String(16), nullable=False),
    Column("relation_source", String(32), nullable=False),
    Column("status", String(32), nullable=False, default="confirmed"),
    Column("updated_by", String(64)),
    Column("updated_at", DateTime, nullable=False),
    UniqueConstraint(
        "hospital_id",
        "db_name",
        "left_table",
        "left_column",
        "right_table",
        "right_column",
        name="uk_table_relation",
    ),
)


def ensure_rule_lineage_schema(engine: Engine) -> dict[str, list[str]]:
    """为旧部署补充计算定义列，并创建医院表关联配置表。"""

    inspector = inspect(engine)
    before_tables = set(inspector.get_table_names())
    metadata.create_all(engine, checkfirst=True)
    after_tables = set(inspect(engine).get_table_names())
    created_tables = [
        table_name
        for table_name in ("med_table_relation",)
        if table_name in after_tables and table_name not in before_tables
    ]

    json_ddl = "TEXT NULL" if engine.dialect.name == "sqlite" else "JSON NULL"
    required_columns = (
        ("med_index_standard", "calculation_definition"),
        ("med_index_hospital_custom", "custom_calculation_patch"),
    )
    added_columns: list[str] = []
    for table_name, column_name in required_columns:
        current_inspector = inspect(engine)
        if not current_inspector.has_table(table_name):
            continue
        existing = {
            str(column["name"])
            for column in current_inspector.get_columns(table_name)
        }
        if column_name in existing:
            continue
        with engine.begin() as conn:
            conn.execute(
                text(
                    f"ALTER TABLE {table_name} "
                    f"ADD COLUMN {column_name} {json_ddl}"
                )
            )
        added_columns.append(f"{table_name}.{column_name}")

    return {
        "added_columns": added_columns,
        "created_tables": created_tables,
    }

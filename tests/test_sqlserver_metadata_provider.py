from __future__ import annotations

from app.db_access.metadata_provider import DBHubMetadataProvider


def test_sqlserver_metadata_provider_uses_catalog_and_schema() -> None:
    calls: list[str] = []

    def execute(sql: str):
        calls.append(sql)
        if "INFORMATION_SCHEMA.TABLES" in sql:
            return [
                {
                    "TABLE_NAME": "INPATIENT_CONSULT_APPLY",
                    "TABLE_COMMENT": "",
                    "TABLE_TYPE": "VIEW",
                }
            ]
        return [
            {
                "TABLE_NAME": "INPATIENT_CONSULT_APPLY",
                "COLUMN_NAME": "APPLY_CONSULT_SENT_AT",
                "DATA_TYPE": "datetime",
                "COLUMN_TYPE": "datetime",
                "IS_NULLABLE": "YES",
            }
        ]

    provider = DBHubMetadataProvider(
        execute,
        dialect="sqlserver",
        schema_name="WINDBA",
    )

    assert provider.mapped_scope_only is True

    tables = provider.list_tables("WIN60_QA_991827")
    columns = provider.list_columns(
        "WIN60_QA_991827", "INPATIENT_CONSULT_APPLY"
    )

    assert tables[0]["table_type"] == "VIEW"
    assert columns[0]["data_type"] == "datetime"
    assert all("DATABASE()" not in sql for sql in calls)
    assert all("TABLE_CATALOG = 'WIN60_QA_991827'" in sql for sql in calls)
    assert all("TABLE_SCHEMA = 'WINDBA'" in sql for sql in calls)

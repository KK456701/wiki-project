from __future__ import annotations

from app import config


def test_business_source_settings_follow_formal_sqlserver_config(monkeypatch) -> None:
    monkeypatch.setenv("BUSINESS_DB_SOURCE_ID", "win60_qa_991827")
    monkeypatch.setenv("BUSINESS_DB_DIALECT", "sqlserver")

    from app.business_source import current_business_source

    settings = current_business_source()

    assert settings.source_id == "win60_qa_991827"
    assert settings.dialect == "sqlserver"
    assert settings.schema == "WINDBA"
    assert settings.database_name == "WIN60_QA_991827"


def test_business_source_defaults_to_company_sqlserver(monkeypatch) -> None:
    monkeypatch.delenv("BUSINESS_DB_SOURCE_ID", raising=False)
    monkeypatch.delenv("BUSINESS_DB_DIALECT", raising=False)
    monkeypatch.setattr(config, "_cache", {})

    from app.business_source import current_business_source

    settings = current_business_source()

    assert settings.source_id == "win60_qa_991827"
    assert settings.dialect == "sqlserver"
    assert settings.schema == "WINDBA"
    assert settings.database_name == "WIN60_QA_991827"


def test_default_business_client_uses_configured_source(monkeypatch) -> None:
    monkeypatch.setenv("BUSINESS_DB_SOURCE_ID", "win60_qa_991827")
    monkeypatch.setenv("BUSINESS_DB_DIALECT", "sqlserver")

    from app.api.main import create_business_db_client

    client = create_business_db_client()

    assert client.source_id == "win60_qa_991827"
    assert client.tool_name == "execute_sql_win60_qa_991827"

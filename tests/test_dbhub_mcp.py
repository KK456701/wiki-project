from __future__ import annotations

from unittest.mock import patch

import pytest

from app.db_access.dbhub_mcp import DBHubMCPClient, DBHubMCPError


def test_execute_sql_exposes_tool_error_message() -> None:
    payload = {
        "result": {
            "isError": True,
            "content": [
                {
                    "type": "text",
                    "text": '{"error":"Incorrect syntax near the keyword TOP."}',
                }
            ],
        }
    }
    client = DBHubMCPClient(
        "http://127.0.0.1:8080/mcp",
        "execute_sql_win60_qa_991827",
    )

    with patch("app.db_access.dbhub_mcp._post_json", return_value=payload):
        with pytest.raises(DBHubMCPError, match="Incorrect syntax"):
            client.execute_sql("SELECT TOP 1 1 AS value")

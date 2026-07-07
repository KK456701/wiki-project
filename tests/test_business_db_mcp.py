from __future__ import annotations

import unittest

from app.db_access.business_db import BusinessDBClient


class FakeMCPClient:
    def __init__(self):
        self.sql = []

    def execute_sql(self, sql: str):
        self.sql.append(sql)
        return [{"TABLE_NAME": "consult_record"}]


class BusinessDBClientTest(unittest.TestCase):
    def test_execute_select_uses_mcp_client(self):
        fake = FakeMCPClient()
        client = BusinessDBClient(
            fake.execute_sql,
            source_id="hospital_demo_data",
            tool_name="execute_sql_hospital_demo_data",
        )

        result = client.execute_select("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES")

        self.assertEqual(fake.sql, ["SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"])
        self.assertEqual(result.row_count, 1)
        self.assertEqual(result.rows[0]["TABLE_NAME"], "consult_record")
        self.assertEqual(result.source, "hospital_demo_data")
        self.assertEqual(result.tool_name, "execute_sql_hospital_demo_data")

    def test_rejects_non_select_sql_before_mcp(self):
        fake = FakeMCPClient()
        client = BusinessDBClient(
            fake.execute_sql,
            source_id="hospital_demo_data",
            tool_name="execute_sql_hospital_demo_data",
        )

        with self.assertRaises(ValueError):
            client.execute_select("DELETE FROM consult_record")

        self.assertEqual(fake.sql, [])


if __name__ == "__main__":
    unittest.main()

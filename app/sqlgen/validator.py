"""SQL safety validator."""

import re

import sqlparse


FORBIDDEN_KEYWORDS = [
    "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
    "CREATE", "REPLACE", "GRANT", "REVOKE", "EXEC", "EXECUTE",
    "LOAD", "INTO OUTFILE", "INTO DUMPFILE",
]


def validate_select_sql(sql_text: str, hospital_id: str, main_table: str) -> dict:
    stripped_sql = sqlparse.format(sql_text, strip_comments=True).strip()
    upper = stripped_sql.upper()

    statements = [
        statement
        for statement in sqlparse.parse(stripped_sql)
        if str(statement).strip().rstrip(";").strip()
    ]
    if len(statements) != 1 or statements[0].get_type() != "SELECT":
        return {"ok": False, "error": "\u53ea\u5141\u8bb8 SELECT \u8bed\u53e5"}

    for kw in FORBIDDEN_KEYWORDS:
        if re.search(rf"\b{kw}\b", upper):
            return {"ok": False, "error": f"\u7981\u6b62\u4f7f\u7528 {kw}"}

    if ";" in stripped_sql.rstrip(";").rstrip():
        return {"ok": False, "error": "\u7981\u6b62\u591a\u8bed\u53e5"}

    # Generated templates currently do not need OR; reject it to avoid bypassing AND filters.
    if re.search(r"\bOR\b", upper):
        return {"ok": False, "error": "\u7981\u6b62\u4f7f\u7528 OR \u6761\u4ef6"}

    if ":start_time" not in stripped_sql or ":end_time" not in stripped_sql:
        return {"ok": False, "error": "\u5fc5\u987b\u5305\u542b :start_time \u548c :end_time \u53c2\u6570"}

    table_tokens = re.findall(r"\b(?:FROM|JOIN)\s+([A-Z0-9_.$`\"]+)", upper)
    normalized_tables = {
        token.strip("`\"").split(".")[-1]
        for token in table_tokens
        if token.strip("`\"")
    }
    expected_table = main_table.strip("`\"").split(".")[-1].upper()
    if expected_table not in normalized_tables:
        return {"ok": False, "error": f"SQL \u5fc5\u987b\u4f7f\u7528\u4e3b\u8868 {main_table}"}

    return {"ok": True, "message": "\u5b89\u5168\u6821\u9a8c\u901a\u8fc7"}

"""SQL 安全校验器。"""

import re


FORBIDDEN_KEYWORDS = [
    "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
    "CREATE", "REPLACE", "GRANT", "REVOKE", "EXEC", "EXECUTE",
    "LOAD", "INTO OUTFILE", "INTO DUMPFILE",
]


def validate_select_sql(sql_text: str, hospital_id: str, main_table: str) -> dict:
    upper = sql_text.upper().strip()

    # 只允许 SELECT
    if not upper.startswith("SELECT"):
        return {"ok": False, "error": "只允许 SELECT 语句"}

    # 禁止危险关键字
    for kw in FORBIDDEN_KEYWORDS:
        if re.search(rf"\b{kw}\b", upper):
            return {"ok": False, "error": f"禁止使用 {kw}"}

    # 禁止多语句
    if ";" in sql_text.rstrip(";").rstrip():
        return {"ok": False, "error": "禁止多语句"}

    # 必须包含时间范围条件
    if ":start_time" not in sql_text or ":end_time" not in sql_text:
        return {"ok": False, "error": "必须包含 :start_time 和 :end_time 参数"}

    # 必须命中主表
    if main_table.upper() not in upper:
        return {"ok": False, "error": f"SQL 必须使用主表 {main_table}"}

    return {"ok": True, "message": "安全校验通过"}

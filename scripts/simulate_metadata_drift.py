"""预览或执行可逆的业务库元数据变更。"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_business_engine
from app.demo_data.metadata_drift import apply_metadata_drift, metadata_drift_sql


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="模拟新增、修改和删除元数据字段")
    parser.add_argument("action", choices=("add", "modify", "remove", "restore"))
    parser.add_argument("--apply", action="store_true", help="实际执行表结构变更")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sql = metadata_drift_sql(args.action)
    if not args.apply:
        print("预览完成，尚未修改数据库。")
        print(sql)
        return
    result = apply_metadata_drift(create_business_engine(), args.action)
    print("元数据演示动作已执行。请在前端重新同步元数据查看变化。")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

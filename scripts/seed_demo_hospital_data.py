"""预览或重建本地医院演示业务数据。"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db.engine import create_business_engine
from app.demo_data.generator import DemoDataOptions, generate_demo_rows, summarize_demo_rows
from app.demo_data.writer import replace_demo_rows, validate_demo_database_name


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成四个核心指标的本地模拟业务数据")
    parser.add_argument("--profile", choices=("baseline", "realistic"), default="realistic")
    parser.add_argument("--start-month", default="2025-01-01")
    parser.add_argument("--months", type=int, default=19)
    parser.add_argument("--seed", type=int, default=20250713)
    parser.add_argument("--hospital-id", default="hospital_001")
    parser.add_argument("--apply", action="store_true", help="实际清空并重建演示业务表")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    options = DemoDataOptions(
        start_month=date.fromisoformat(args.start_month),
        month_count=args.months,
        hospital_id=args.hospital_id,
        profile=args.profile,
        seed=args.seed,
    )
    rows = generate_demo_rows(options)
    summary = summarize_demo_rows(rows)
    if not args.apply:
        print("预览完成，尚未写入数据库。确认后增加 --apply。")
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return
    engine = create_business_engine()
    validate_demo_database_name(engine.url.database)
    result = replace_demo_rows(engine, rows)
    print("演示业务数据已重建。")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

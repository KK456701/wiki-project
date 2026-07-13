"""预览或通过正式监控服务生成历史运行基线。"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.demo_data.monitoring_baseline import (
    DEMO_MONITORING_RULES,
    build_monitoring_periods,
    demo_plan_payload,
    json_safe,
    seed_monitoring_baseline,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成四个指标的历史监控基线")
    parser.add_argument("--start-month", default="2025-01-01")
    parser.add_argument("--months", type=int, default=19)
    parser.add_argument("--hospital-id", default="hospital_001")
    parser.add_argument("--apply", action="store_true", help="实际执行历史指标运算")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    periods = build_monitoring_periods(date.fromisoformat(args.start_month), args.months)
    preview = {
        "plans": [
            demo_plan_payload(rule_id, args.hospital_id)["plan_id"]
            for rule_id in DEMO_MONITORING_RULES
        ],
        "periods": periods,
        "planned_runs": len(periods) * len(DEMO_MONITORING_RULES),
    }
    if not args.apply:
        print("预览完成，尚未执行历史运算。")
        print(json.dumps(preview, ensure_ascii=False, indent=2))
        return
    from app.monitoring.factory import create_monitoring_service

    result = seed_monitoring_baseline(
        create_monitoring_service(),
        periods,
        hospital_id=args.hospital_id,
    )
    print("历史监控基线已通过正式运算链路生成。")
    print(json.dumps(json_safe(result), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

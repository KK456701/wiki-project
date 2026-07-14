import unittest

from app.sqlgen.explanation import (
    format_generation_explanation,
    format_trial_explanation,
)


SPEC = {
    "rule_id": "MQSI2025_005",
    "rule_name": "急会诊及时到位率",
    "description": "急会诊请求发出后，在规定时间内到达现场的急会诊占比。",
    "default_params": {
        "arrive_minutes_threshold": 10,
        "consult_type_value": "急会诊",
    },
    "numerator": {
        "name": "及时到位急会诊次数",
        "logic": [
            "consult_type = consult_type_value",
            "0 <= arrive_minutes <= arrive_minutes_threshold",
        ],
    },
    "denominator": {
        "name": "同期急会诊总次数",
        "logic": ["consult_type = consult_type_value"],
    },
    "required_business_fields": [
        "hospital_id",
        "consult_type",
        "request_time",
        "arrive_time",
    ],
}

FIELD_CONTRACT = {
    "business_fields": {
        "hospital_id": {"desc": "医院ID"},
        "consult_type": {"desc": "会诊类型"},
        "request_time": {"desc": "急会诊申请时间"},
        "arrive_time": {"desc": "急会诊到达时间"},
    }
}

MAPPING = {
    "db_name": "hospital_demo_data",
    "main_table": "consult_record",
    "dialect": "mysql",
    "fields": {
        "hospital_id": "consult_record.hospital_id",
        "consult_type": "consult_record.consult_type",
        "request_time": "consult_record.request_time",
        "arrive_time": "consult_record.arrive_time",
    },
}

HOSPITAL_RULE = {
    "rule_id": "MQSI2025_005",
    "rule_name": "急会诊及时到位率",
    "effective_level": "hospital",
    "effective_params": {
        "arrive_minutes_threshold": 20,
        "consult_type_value": "急会诊",
    },
    "national_params": {
        "arrive_minutes_threshold": 10,
        "consult_type_value": "急会诊",
    },
    "national_version": "2025",
    "hospital_version": 1,
    "overridden_fields": ["arrive_minutes_threshold", "numerator_rule"],
    "numerator_rule": "急会诊请求发出后0至20分钟内到位的急会诊次数",
}

GENERATION_RESULT = {
    "sql_id": "SQL_80",
    "sql_text": "SELECT 80 AS index_value FROM consult_record",
    "validation": {"ok": True, "message": "安全校验通过"},
    "params": {
        "arrive_minutes_threshold": 20,
        "consult_type_value": "急会诊",
    },
}


def _trial_result(numerator=8, denominator=10, result_value=80.0):
    return {
        **GENERATION_RESULT,
        "trial_run": {
            "run_id": "RUN_80",
            "status": "success",
            "result_value": result_value,
            "numerator_count": numerator,
            "denominator_count": denominator,
            "source": "hospital_demo_data",
            "stat_start": "2026-07-01 00:00:00",
            "stat_end": "2026-08-01 00:00:00",
            "duration_ms": 12,
            "error_message": None,
        },
    }


class SqlExplanationTest(unittest.TestCase):
    def _generation(self, effective_rule=None):
        return format_generation_explanation(
            result=GENERATION_RESULT,
            effective_rule=effective_rule or HOSPITAL_RULE,
            spec=SPEC,
            field_contract=FIELD_CONTRACT,
            mapping=MAPPING,
            hospital_id="hospital_001",
            stat_start="2026-07-01 00:00:00",
            stat_end="2026-08-01 00:00:00",
        )

    def _trial(self, result, effective_rule=None):
        return format_trial_explanation(
            result=result,
            effective_rule=effective_rule or HOSPITAL_RULE,
            spec=SPEC,
            field_contract=FIELD_CONTRACT,
            mapping=MAPPING,
            hospital_id="hospital_001",
            stat_start="2026-07-01 00:00:00",
            stat_end="2026-08-01 00:00:00",
        )

    def test_generation_explains_hospital_caliber_before_sql(self):
        answer = self._generation()

        self.assertIn("本院生效口径", answer)
        self.assertIn("20分钟", answer)
        self.assertIn("标准值：10分钟", answer)
        self.assertIn("hospital_demo_data.consult_record", answer)
        self.assertIn("急会诊申请时间", answer)
        self.assertIn("| 计算项 | 业务解释 | 本院实际条件 |", answer)
        self.assertIn("| 业务字段 | 业务含义 | 医院字段 |", answer)
        self.assertLess(answer.index("| 计算项 |"), answer.index("```sql"))

    def test_trial_explains_eighty_percent_from_aggregates(self):
        answer = self._trial(_trial_result())

        self.assertIn("8 / 10 x 100% = 80%", answer)
        self.assertIn("## 为什么这样计算", answer)
        self.assertIn("申请至到位耗时为0至20分钟", answer)
        self.assertIn("未进入分子", answer)
        self.assertIn("| 分子 | 8 |", answer)
        self.assertIn("| 分母 | 10 |", answer)

    def test_zero_denominator_is_no_data_not_zero_percent(self):
        answer = self._trial(_trial_result(0, 0, 0.0))

        self.assertIn("本期没有符合分母条件的数据", answer)
        self.assertNotIn("0 / 0 x 100%", answer)

    def test_numerator_greater_than_denominator_is_abnormal(self):
        answer = self._trial(_trial_result(11, 10, 110.0))

        self.assertIn("分子大于分母", answer)
        self.assertIn("结果异常", answer)

    def test_legacy_sql_without_counts_is_labeled(self):
        answer = self._trial(_trial_result(None, 10, 80.0))

        self.assertIn("旧版 SQL 未返回分子分母", answer)

    def test_standard_rule_does_not_claim_hospital_customization(self):
        standard_rule = {
            **HOSPITAL_RULE,
            "effective_level": "national",
            "effective_params": SPEC["default_params"],
            "overridden_fields": [],
            "hospital_version": None,
        }

        answer = self._generation(standard_rule)

        self.assertIn("标准口径", answer)
        self.assertNotIn("本院定制", answer)


if __name__ == "__main__":
    unittest.main()

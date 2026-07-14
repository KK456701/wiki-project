import copy
import unittest
from pathlib import Path

import yaml

from app.rules.calculation import parse_calculation_definition
from app.rules.lineage import build_indicator_lineage
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

URGENT_LINEAGE = {
    "denominator_name": "同期急会诊总次数",
    "numerator_name": "及时到位急会诊次数",
    "db_name": "hospital_demo_data",
    "main_table": "consult_record",
    "physical_tables": ["consult_record"],
    "field_items": [
        {
            "business_field": "hospital_id",
            "label": "医院",
            "physical_field": "consult_record.hospital_id",
        },
        {
            "business_field": "request_time",
            "label": "急会诊申请时间",
            "physical_field": "consult_record.request_time",
        },
        {
            "business_field": "consult_type",
            "label": "会诊类型",
            "physical_field": "consult_record.consult_type",
        },
        {
            "business_field": "arrive_time",
            "label": "急会诊到位时间",
            "physical_field": "consult_record.arrive_time",
        },
    ],
    "denominator_rows": [
        {
            "label": "限定医院",
            "business_fields": ["hospital_id"],
            "physical_fields": ["consult_record.hospital_id"],
            "field_items": [
                {
                    "business_field": "hospital_id",
                    "label": "医院",
                    "physical_field": "consult_record.hospital_id",
                }
            ],
            "condition_text": "医院等于hospital_001",
            "source": "当前医院",
            "effect": "限定分子和分母共同统计范围",
        },
        {
            "label": "限定统计区间",
            "business_fields": ["request_time"],
            "physical_fields": ["consult_record.request_time"],
            "field_items": [
                {
                    "business_field": "request_time",
                    "label": "急会诊申请时间",
                    "physical_field": "consult_record.request_time",
                }
            ],
            "condition_text": "急会诊申请时间为本次统计区间",
            "source": "本次统计区间",
            "effect": "限定分子和分母共同统计范围",
        },
        {
            "label": "分母筛选条件",
            "business_fields": ["consult_type"],
            "physical_fields": ["consult_record.consult_type"],
            "field_items": [
                {
                    "business_field": "consult_type",
                    "label": "会诊类型",
                    "physical_field": "consult_record.consult_type",
                }
            ],
            "condition_text": "会诊类型等于急会诊",
            "source": "标准口径",
            "effect": "满足时进入分母",
        },
        {
            "condition_id": "denominator_aggregate",
            "label": "计数方式",
            "business_fields": [],
            "physical_fields": [],
            "field_items": [],
            "condition_text": "每条符合条件的业务记录计1次",
            "source": "标准定义",
            "effect": "得到同期急会诊总次数",
        },
    ],
    "numerator_rows": [
        {
            "condition_id": "inherits_denominator",
            "label": "继承分母",
            "business_fields": [],
            "physical_fields": [],
            "field_items": [],
            "condition_text": "先满足全部分母条件",
            "source": "指标定义",
            "effect": "分子一定是分母的子集",
        },
        {
            "label": "判断申请至到位耗时",
            "business_fields": ["request_time", "arrive_time"],
            "physical_fields": [
                "consult_record.request_time",
                "consult_record.arrive_time",
            ],
            "field_items": [
                {
                    "business_field": "request_time",
                    "label": "急会诊申请时间",
                    "physical_field": "consult_record.request_time",
                },
                {
                    "business_field": "arrive_time",
                    "label": "急会诊到位时间",
                    "physical_field": "consult_record.arrive_time",
                },
            ],
            "condition_text": "申请至到位耗时为0至20分钟",
            "derivation_text": "急会诊到位时间减急会诊申请时间，换算为分钟",
            "source": "本院版本 v1",
            "effect": "在分母基础上满足时进入分子",
        },
        {
            "condition_id": "numerator_aggregate",
            "label": "计数方式",
            "business_fields": [],
            "physical_fields": [],
            "field_items": [],
            "condition_text": "每条符合条件的业务记录计1次",
            "source": "标准定义",
            "effect": "得到及时到位急会诊次数",
        },
    ],
    "caliber_rows": [
        {
            "parameter": "arrive_minutes_threshold",
            "current_value": "20分钟",
            "standard_value": "10分钟",
            "condition_name": "判断申请至到位耗时",
            "physical_fields": [
                "consult_record.request_time",
                "consult_record.arrive_time",
            ],
            "effect_scope": "只改变分子，不改变分母",
            "version": "本院版本 v1",
        }
    ],
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


def _visible_part(answer: str) -> str:
    return answer.split(":::details", 1)[0]


class SqlExplanationTest(unittest.TestCase):
    def _generation(self, effective_rule=None, lineage=URGENT_LINEAGE):
        return format_generation_explanation(
            result=GENERATION_RESULT,
            effective_rule=effective_rule or HOSPITAL_RULE,
            lineage=lineage,
            hospital_id="hospital_001",
            stat_start="2026-07-01 00:00:00",
            stat_end="2026-08-01 00:00:00",
        )

    def _trial(self, result, effective_rule=None, lineage=URGENT_LINEAGE):
        return format_trial_explanation(
            result=result,
            effective_rule=effective_rule or HOSPITAL_RULE,
            lineage=lineage,
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
        self.assertIn("## 分母如何取数", answer)
        self.assertIn("consult_record.hospital_id", answer)
        self.assertIn("consult_record.consult_type", answer)
        self.assertIn("consult_record.request_time", answer)
        self.assertIn("## 分子如何从分母中筛选", answer)
        self.assertIn("分子一定是分母的子集", answer)
        self.assertIn("consult_record.arrive_time", answer)
        self.assertIn("急会诊到位时间减急会诊申请时间", answer)
        self.assertIn("## 本院口径作用在哪里", answer)
        self.assertIn("只改变分子，不改变分母", answer)
        self.assertLess(answer.index("## 分母如何取数"), answer.index("```sql"))

    def test_generation_defaults_to_doctor_friendly_explanation(self):
        answer = self._generation()
        visible = _visible_part(answer)

        self.assertIn("本院规定的20分钟", visible)
        self.assertIn("只影响分子，不改变分母", visible)
        self.assertIn("| 本院口径 |", visible)
        self.assertNotIn("| 本院规则 |", visible)
        self.assertIn("## 数据从哪里来", visible)
        self.assertIn("hospital_demo_data", visible)
        self.assertIn("consult_record", visible)
        self.assertIn("## 分子与分母怎么计算", visible)
        self.assertIn("急会诊申请时间：`consult_record.request_time`", visible)
        self.assertIn("急会诊到位时间：`consult_record.arrive_time`", visible)
        self.assertIn("到位时间减申请时间", visible)
        self.assertIn("分子 = SUM(满足分子条件：是=1，否=0)", visible)
        self.assertIn("指标值 = 分子 / 分母 x 100%", visible)
        self.assertIn("## 系统实际执行的步骤", visible)
        self.assertIn("1. **筛选统计范围**", visible)
        self.assertIn("2. **计算时间差**", visible)
        self.assertIn("TIMESTAMPDIFF", visible)
        self.assertNotIn("<br>", visible)
        self.assertNotIn("```sql", visible)
        self.assertIn(":::details 查看技术详情（供信息科和实施人员）", answer)

    def test_trial_explains_eighty_percent_from_aggregates(self):
        answer = self._trial(_trial_result())

        self.assertIn("8 / 10 x 100% = 80%", answer)
        self.assertIn("## 分母如何取数", answer)
        self.assertIn("## 分子如何从分母中筛选", answer)
        self.assertIn("申请至到位耗时为0至20分钟", answer)
        self.assertIn("未达到要求", answer)
        self.assertIn("| 达到要求（分子） | 8 |", answer)
        self.assertIn("| 统计范围（分母） | 10 |", answer)

    def test_trial_explains_result_in_plain_language(self):
        answer = self._trial(_trial_result())
        visible = _visible_part(answer)

        self.assertIn("本期共有10次急会诊进入统计范围", visible)
        self.assertIn("其中8次在本院规定的20分钟内到位", visible)
        self.assertIn("另有2次未在规定时间内到位", visible)
        self.assertIn("8 / 10 x 100% = 80%", visible)
        self.assertIn("统计范围（分母）", visible)
        self.assertIn("达到要求（分子）", visible)
        self.assertIn("consult_record.request_time", visible)
        self.assertNotIn("```sql", visible)

    def test_distinct_count_uses_unique_subject_without_boolean_sum(self):
        lineage = copy.deepcopy(URGENT_LINEAGE)
        admission_item = {
            "business_field": "admission_id",
            "label": "入院流水号",
            "physical_field": "inpatient_transfer_record.admission_id",
        }
        transfer_item = {
            "business_field": "transfer_time",
            "label": "转科时间",
            "physical_field": "inpatient_transfer_record.transfer_time",
        }
        lineage.update(
            {
                "denominator_name": "同期入院患者总人次数",
                "numerator_name": "发生转科的入院人次数",
                "main_table": "inpatient_transfer_record",
                "physical_tables": ["inpatient_transfer_record"],
                "field_items": [admission_item, transfer_item],
                "caliber_rows": [],
            }
        )
        lineage["denominator_rows"] = [
            {
                "condition_id": "denominator_aggregate",
                "field_items": [admission_item],
                "business_fields": ["admission_id"],
                "physical_fields": [admission_item["physical_field"]],
                "condition_text": "按入院流水号去重计数",
            }
        ]
        lineage["numerator_rows"] = [
            {
                "condition_id": "inherits_denominator",
                "field_items": [],
                "business_fields": [],
                "physical_fields": [],
                "condition_text": "先满足全部分母条件",
            },
            {
                "condition_id": "has_transfer",
                "field_items": [transfer_item],
                "business_fields": ["transfer_time"],
                "physical_fields": [transfer_item["physical_field"]],
                "condition_text": "转科时间不为空",
            },
            {
                "condition_id": "numerator_aggregate",
                "field_items": [admission_item],
                "business_fields": ["admission_id"],
                "physical_fields": [admission_item["physical_field"]],
                "condition_text": "按入院流水号去重计数",
            },
        ]

        visible = _visible_part(self._generation(lineage=lineage))

        self.assertIn(
            "分母 = COUNT(DISTINCT inpatient_transfer_record.admission_id)",
            visible,
        )
        self.assertIn(
            "分子 = COUNT(DISTINCT inpatient_transfer_record.admission_id)",
            visible,
        )
        self.assertNotIn("**计算时间差**", visible)
        self.assertIn("2. **统计分母**", visible)
        self.assertIn("3. **统计分子**", visible)
        self.assertNotIn("满足条件记为 1", visible)

    def test_four_supported_indicators_explain_their_real_tables_and_fields(self):
        cases = [
            (
                "MQSI2025_001",
                "inpatient_transfer_record",
                ["入院流水号", "入院时间", "转科时间"],
                "转科时间减入院时间",
            ),
            (
                "MQSI2025_005",
                "consult_record",
                ["会诊类型", "急会诊申请时间", "急会诊到位时间"],
                "到位时间减申请时间",
            ),
            (
                "MQSI2025_014",
                "critical_rescue_record",
                ["患者严重程度", "抢救结果", "抢救时间"],
                None,
            ),
            (
                "MQSI2025_035",
                "intraoperative_transfusion_record",
                ["术中输血标志", "自体血回输标志", "手术时间"],
                None,
            ),
        ]

        for rule_id, table, field_labels, derived_operation in cases:
            with self.subTest(rule_id=rule_id):
                spec_path = next(
                    Path("core-rules-wiki/sql-specs").glob(
                        f"{rule_id}*/rule_sql_spec.yaml"
                    )
                )
                mapping_path = Path(
                    f"core-rules-wiki/hospital-mappings/hospital_001/{rule_id}.yaml"
                )
                spec = yaml.safe_load(spec_path.read_text(encoding="utf-8"))
                mapping = yaml.safe_load(mapping_path.read_text(encoding="utf-8"))
                params = {
                    **(spec.get("default_params") or {}),
                    "hospital_id": "hospital_001",
                    "start_time": "2026-07-01 00:00:00",
                    "end_time": "2026-08-01 00:00:00",
                }
                effective_rule = {
                    "rule_id": rule_id,
                    "rule_name": spec["rule_name"],
                    "effective_level": "national",
                    "effective_params": params,
                    "national_params": spec.get("default_params") or {},
                    "national_version": "2025",
                    "overridden_fields": [],
                }
                lineage = build_indicator_lineage(
                    parse_calculation_definition(spec["calculation"]),
                    mapping,
                    params,
                    effective_rule,
                    params["start_time"],
                    params["end_time"],
                )
                answer = format_generation_explanation(
                    result={
                        **GENERATION_RESULT,
                        "dialect": mapping["dialect"],
                        "params": params,
                    },
                    effective_rule=effective_rule,
                    lineage=lineage,
                    hospital_id="hospital_001",
                    stat_start=params["start_time"],
                    stat_end=params["end_time"],
                )
                visible = _visible_part(answer)

                self.assertIn(f"数据库：`{mapping['db_name']}`", visible)
                self.assertIn(f"`{table}`", visible)
                for field_label in field_labels:
                    self.assertIn(field_label, visible)
                self.assertIn("指标值 = 分子 / 分母 x 100%", visible)
                if derived_operation:
                    self.assertIn(derived_operation, visible)
                    self.assertIn("**计算时间差**", visible)
                else:
                    self.assertNotIn("**计算时间差**", visible)

    def test_visible_explanation_lists_multiple_tables_without_guessing_join(self):
        lineage = copy.deepcopy(URGENT_LINEAGE)
        lineage["physical_tables"] = ["consult_arrival", "consult_record"]
        arrival_item = next(
            item
            for item in lineage["field_items"]
            if item["business_field"] == "arrive_time"
        )
        arrival_item["physical_field"] = "consult_arrival.arrive_time"
        timely = next(
            row
            for row in lineage["numerator_rows"]
            if row.get("derivation_text")
        )
        timely["field_items"][1]["physical_field"] = (
            "consult_arrival.arrive_time"
        )

        visible = _visible_part(self._generation(lineage=lineage))

        self.assertIn("`consult_record`", visible)
        self.assertIn("`consult_arrival`", visible)
        self.assertNotIn("JOIN", visible)

    def test_missing_physical_mapping_is_marked_instead_of_guessed(self):
        lineage = copy.deepcopy(URGENT_LINEAGE)
        timely = next(
            row
            for row in lineage["numerator_rows"]
            if row.get("derivation_text")
        )
        timely["field_items"][1]["physical_field"] = "未映射(arrive_time)"

        visible = _visible_part(self._generation(lineage=lineage))

        self.assertIn("急会诊到位时间：尚未映射", visible)
        self.assertNotIn("TIMESTAMPDIFF", visible)

    def test_trial_count_rows_include_safe_detail_actions(self):
        answer = self._trial(_trial_result())

        self.assertIn("| 统计项 | 数量 | 说明 | 操作 |", answer)
        self.assertIn("{{detail:RUN_80:denominator}}", answer)
        self.assertIn("{{detail:RUN_80:numerator}}", answer)
        self.assertIn("{{detail:RUN_80:unmatched}}", answer)
        self.assertNotIn("{{detail:RUN_80:result}}", answer)

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

    def test_legacy_result_does_not_guess_field_relationships(self):
        answer = self._generation(lineage={})

        self.assertIn("字段关系尚未结构化", answer)
        self.assertNotIn("consult_record.arrive_time", answer)


if __name__ == "__main__":
    unittest.main()

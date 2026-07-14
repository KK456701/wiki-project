import copy
import unittest

from app.rules.calculation import (
    collect_business_dependencies,
    merge_calculation_patch,
    parse_calculation_definition,
    validate_calculation_definition,
)


URGENT_CONSULT_DEFINITION = {
    "schema_version": 1,
    "scope": {
        "conditions": [
            {
                "id": "hospital_scope",
                "field": "hospital_id",
                "operator": "equals",
                "parameter": "hospital_id",
            },
            {
                "id": "period_scope",
                "field": "request_time",
                "operator": "half_open_range",
                "parameters": ["start_time", "end_time"],
            },
        ]
    },
    "derived_fields": {
        "arrive_minutes": {
            "name": "申请至到位耗时",
            "operation": "timestamp_diff_minutes",
            "source_fields": ["request_time", "arrive_time"],
        }
    },
    "denominator": {
        "name": "同期急会诊总次数",
        "inherits": "scope",
        "conditions": [
            {
                "id": "urgent_consult",
                "field": "consult_type",
                "operator": "equals",
                "parameter": "consult_type_value",
            }
        ],
        "aggregate": {"method": "count_rows"},
    },
    "numerator": {
        "name": "及时到位急会诊次数",
        "inherits": "denominator",
        "conditions": [
            {
                "id": "timely_arrival",
                "field": "arrive_minutes",
                "operator": "inclusive_range",
                "values": [0, "arrive_minutes_threshold"],
            }
        ],
        "aggregate": {"method": "count_rows"},
    },
    "result": {
        "operation": "ratio_percent",
        "numerator": "numerator",
        "denominator": "denominator",
    },
}

BUSINESS_FIELDS = {
    "hospital_id": {"type": "string"},
    "consult_type": {"type": "string"},
    "request_time": {"type": "datetime"},
    "arrive_time": {"type": "datetime"},
}

PARAMS = {
    "hospital_id": "hospital_001",
    "start_time": "2026-07-01 00:00:00",
    "end_time": "2026-08-01 00:00:00",
    "consult_type_value": "急会诊",
    "arrive_minutes_threshold": 20,
}


class CalculationDefinitionTest(unittest.TestCase):
    def test_detail_fields_accept_business_and_derived_fields_without_changing_sql_dependencies(self) -> None:
        payload = copy.deepcopy(URGENT_CONSULT_DEFINITION)
        payload["detail_fields"] = [
            {"field": "patient_id", "label": "患者标识", "sensitivity": "patient_id"},
            {"field": "dept_id", "label": "科室", "sensitivity": "none"},
            {"field": "arrive_minutes", "label": "到位耗时", "sensitivity": "none"},
        ]
        fields = {
            **BUSINESS_FIELDS,
            "patient_id": {"type": "string"},
            "dept_id": {"type": "string"},
        }

        definition = parse_calculation_definition(payload)

        self.assertEqual(
            [item.field for item in definition.detail_fields],
            ["patient_id", "dept_id", "arrive_minutes"],
        )
        self.assertEqual(definition.detail_fields[0].sensitivity, "patient_id")
        self.assertEqual(validate_calculation_definition(definition, fields, PARAMS), [])
        self.assertEqual(
            collect_business_dependencies(definition),
            {"hospital_id", "consult_type", "request_time", "arrive_time"},
        )

    def test_detail_fields_reject_unknown_and_duplicate_fields(self) -> None:
        payload = copy.deepcopy(URGENT_CONSULT_DEFINITION)
        payload["detail_fields"] = [
            {"field": "unknown_field", "label": "未知字段"},
            {"field": "unknown_field", "label": "重复字段"},
        ]

        errors = validate_calculation_definition(
            parse_calculation_definition(payload), BUSINESS_FIELDS, PARAMS
        )

        self.assertIn("明细字段 unknown_field 未在业务字段或派生字段中定义", errors)
        self.assertIn("明细字段重复 unknown_field", errors)

    def test_collects_derived_source_fields(self) -> None:
        definition = parse_calculation_definition(URGENT_CONSULT_DEFINITION)

        self.assertEqual(
            collect_business_dependencies(definition),
            {"hospital_id", "consult_type", "request_time", "arrive_time"},
        )
        self.assertEqual(
            validate_calculation_definition(definition, BUSINESS_FIELDS, PARAMS),
            [],
        )

    def test_reports_unknown_field_and_derived_cycle(self) -> None:
        payload = copy.deepcopy(URGENT_CONSULT_DEFINITION)
        payload["scope"]["conditions"][0]["field"] = "unknown_field"
        payload["derived_fields"]["arrive_minutes"]["source_fields"] = [
            "second_derived"
        ]
        payload["derived_fields"]["second_derived"] = {
            "name": "循环字段",
            "operation": "timestamp_diff_minutes",
            "source_fields": ["arrive_minutes", "arrive_time"],
        }

        errors = validate_calculation_definition(
            parse_calculation_definition(payload), BUSINESS_FIELDS, PARAMS
        )

        self.assertTrue(any("未定义字段 unknown_field" in item for item in errors))
        self.assertTrue(any("循环依赖" in item for item in errors))

    def test_reports_missing_parameter(self) -> None:
        params = {key: value for key, value in PARAMS.items() if key != "end_time"}

        errors = validate_calculation_definition(
            parse_calculation_definition(URGENT_CONSULT_DEFINITION),
            BUSINESS_FIELDS,
            params,
        )

        self.assertIn("条件 period_scope 引用了未配置参数 end_time", errors)

    def test_merge_patch_changes_allowed_node_without_mutating_standard(self) -> None:
        base = copy.deepcopy(URGENT_CONSULT_DEFINITION)
        patch = {
            "numerator": {
                "conditions": [
                    {
                        "id": "timely_arrival",
                        "field": "arrive_minutes",
                        "operator": "inclusive_range",
                        "values": [0, "hospital_arrive_minutes_threshold"],
                    }
                ]
            }
        }

        merged = merge_calculation_patch(base, patch)

        self.assertEqual(
            merged["numerator"]["conditions"][0]["values"][1],
            "hospital_arrive_minutes_threshold",
        )
        self.assertEqual(
            base["numerator"]["conditions"][0]["values"][1],
            "arrive_minutes_threshold",
        )

    def test_merge_patch_rejects_schema_version_and_deletion(self) -> None:
        with self.assertRaisesRegex(ValueError, "不允许修改 schema_version"):
            merge_calculation_patch(URGENT_CONSULT_DEFINITION, {"schema_version": 2})

        with self.assertRaisesRegex(ValueError, "不允许删除计算定义节点"):
            merge_calculation_patch(
                URGENT_CONSULT_DEFINITION, {"numerator": {"conditions": None}}
            )


if __name__ == "__main__":
    unittest.main()

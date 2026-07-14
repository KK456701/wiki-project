import unittest

from app.rules.calculation import parse_calculation_definition
from app.rules.lineage import build_indicator_lineage


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

HOSPITAL_MAPPING = {
    "db_name": "hospital_demo_data",
    "main_table": "consult_record",
    "fields": {
        "hospital_id": "consult_record.hospital_id",
        "consult_type": "consult_record.consult_type",
        "request_time": "consult_record.request_time",
        "arrive_time": "consult_record.arrive_time",
    },
}

HOSPITAL_RULE = {
    "effective_level": "hospital",
    "hospital_version": 1,
    "national_version": "2025",
    "national_params": {
        "arrive_minutes_threshold": 10,
        "consult_type_value": "急会诊",
    },
    "overridden_fields": ["arrive_minutes_threshold", "numerator_rule"],
}

PARAMS = {
    "hospital_id": "hospital_001",
    "start_time": "2026-07-01 00:00:00",
    "end_time": "2026-08-01 00:00:00",
    "consult_type_value": "急会诊",
    "arrive_minutes_threshold": 20,
}


class IndicatorLineageTest(unittest.TestCase):
    def test_links_denominator_numerator_and_hospital_caliber(self) -> None:
        lineage = build_indicator_lineage(
            parse_calculation_definition(URGENT_CONSULT_DEFINITION),
            HOSPITAL_MAPPING,
            PARAMS,
            HOSPITAL_RULE,
            PARAMS["start_time"],
            PARAMS["end_time"],
        )

        denominator_fields = {
            field
            for row in lineage["denominator_rows"]
            for field in row["physical_fields"]
        }
        self.assertEqual(
            denominator_fields,
            {
                "consult_record.hospital_id",
                "consult_record.consult_type",
                "consult_record.request_time",
            },
        )
        inheritance = lineage["numerator_rows"][0]
        self.assertEqual(inheritance["condition_id"], "inherits_denominator")
        self.assertEqual(inheritance["effect"], "分子一定是分母的子集")
        timely = next(
            row
            for row in lineage["numerator_rows"]
            if row["condition_id"] == "timely_arrival"
        )
        self.assertEqual(
            timely["physical_fields"],
            ["consult_record.request_time", "consult_record.arrive_time"],
        )
        self.assertEqual(timely["condition_text"], "申请至到位耗时为0至20分钟")
        self.assertEqual(
            timely["derivation_text"],
            "急会诊到位时间减急会诊申请时间，换算为分钟",
        )
        self.assertEqual(timely["source"], "本院版本 v1")
        self.assertEqual(lineage["caliber_rows"][0]["standard_value"], "10分钟")
        self.assertEqual(lineage["caliber_rows"][0]["current_value"], "20分钟")
        self.assertEqual(
            lineage["caliber_rows"][0]["effect_scope"],
            "只改变分子，不改变分母",
        )
        self.assertEqual(lineage["physical_tables"], ["consult_record"])

    def test_distinct_aggregate_names_the_counting_field(self) -> None:
        payload = {
            **URGENT_CONSULT_DEFINITION,
            "derived_fields": {},
            "denominator": {
                "name": "同期入院患者总人次数",
                "inherits": "scope",
                "conditions": [],
                "aggregate": {"method": "count_distinct", "field": "admission_id"},
            },
            "numerator": {
                "name": "发生转科的入院人次数",
                "inherits": "denominator",
                "conditions": [
                    {
                        "id": "has_transfer",
                        "field": "transfer_time",
                        "operator": "is_not_null",
                    }
                ],
                "aggregate": {"method": "count_distinct", "field": "admission_id"},
            },
        }
        payload["scope"] = {
            "conditions": [
                {
                    "id": "period_scope",
                    "field": "admit_time",
                    "operator": "half_open_range",
                    "parameters": ["start_time", "end_time"],
                }
            ]
        }
        mapping = {
            "db_name": "hospital_demo_data",
            "main_table": "inpatient_transfer_record",
            "fields": {
                "admission_id": "inpatient_transfer_record.admission_id",
                "admit_time": "inpatient_transfer_record.admit_time",
                "transfer_time": "inpatient_transfer_record.transfer_time",
            },
        }

        lineage = build_indicator_lineage(
            parse_calculation_definition(payload),
            mapping,
            PARAMS,
            {"effective_level": "national", "overridden_fields": []},
            PARAMS["start_time"],
            PARAMS["end_time"],
        )

        aggregate = next(
            row
            for row in lineage["denominator_rows"]
            if row["condition_id"] == "denominator_aggregate"
        )
        self.assertEqual(aggregate["condition_text"], "按入院流水号去重计数")
        self.assertEqual(
            aggregate["physical_fields"],
            ["inpatient_transfer_record.admission_id"],
        )

    def test_lists_multiple_physical_tables_without_guessing_join(self) -> None:
        mapping = {
            **HOSPITAL_MAPPING,
            "fields": {
                **HOSPITAL_MAPPING["fields"],
                "arrive_time": "consult_arrival.arrive_time",
            },
        }

        lineage = build_indicator_lineage(
            parse_calculation_definition(URGENT_CONSULT_DEFINITION),
            mapping,
            PARAMS,
            HOSPITAL_RULE,
            PARAMS["start_time"],
            PARAMS["end_time"],
        )

        self.assertEqual(
            lineage["physical_tables"], ["consult_arrival", "consult_record"]
        )
        self.assertNotIn("join", lineage)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

from sqlalchemy import Connection, Engine, text

from app.rules.calculation import (
    parse_calculation_definition,
    validate_calculation_definition,
)
from app.rules.schema import ensure_rule_lineage_schema
from app.sqlgen.spec_loader import load_field_contract, load_rule_sql_spec


FOUR_INDICATOR_CODES = (
    "MQSI2025_001",
    "MQSI2025_005",
    "MQSI2025_014",
    "MQSI2025_035",
)


_SEEDS: dict[str, dict[str, Any]] = {
    "MQSI2025_001": {
        "index_name": "患者入院 48 小时内转科的比例",
        "index_type": "三级查房制度",
        "numerator_rule": "入院后0至48小时内转科且转入、转出科室均非ICU的入院人次数",
        "denominator_rule": "同期入院患者总人次数（按入院流水号去重）",
        "filter_rule": "按入院时间纳入统计周期",
        "exclude_rule": "排除转入ICU或从ICU转出的转科记录",
        "rule_params": {"transfer_minutes_threshold": 2880, "excluded_dept_id": "ICU"},
        "main_table": "inpatient_transfer_record",
        "fields": {
            "hospital_id": ("hospital_id", "string"),
            "admission_id": ("admission_id", "string"),
            "admit_time": ("admit_time", "datetime"),
            "transfer_time": ("transfer_time", "datetime"),
            "from_dept_id": ("from_dept_id", "string"),
            "to_dept_id": ("to_dept_id", "string"),
        },
        "standard_sql": """SELECT
  CASE WHEN COUNT(DISTINCT {{ fields.admission_id }}) = 0 THEN 0
       ELSE ROUND(COUNT(DISTINCT CASE
         WHEN {{ fields.transfer_time }} IS NOT NULL
          AND TIMESTAMPDIFF(MINUTE, {{ fields.admit_time }}, {{ fields.transfer_time }}) BETWEEN 0 AND :transfer_minutes_threshold
          AND COALESCE({{ fields.from_dept_id }}, '') <> :excluded_dept_id
         AND COALESCE({{ fields.to_dept_id }}, '') <> :excluded_dept_id
         THEN {{ fields.admission_id }} END) / COUNT(DISTINCT {{ fields.admission_id }}) * 100, 2)
  END AS index_value,
  COUNT(DISTINCT CASE
    WHEN {{ fields.transfer_time }} IS NOT NULL
     AND TIMESTAMPDIFF(MINUTE, {{ fields.admit_time }}, {{ fields.transfer_time }}) BETWEEN 0 AND :transfer_minutes_threshold
     AND COALESCE({{ fields.from_dept_id }}, '') <> :excluded_dept_id
     AND COALESCE({{ fields.to_dept_id }}, '') <> :excluded_dept_id
    THEN {{ fields.admission_id }} END) AS numerator_count,
  COUNT(DISTINCT {{ fields.admission_id }}) AS denominator_count,
  COUNT(DISTINCT {{ fields.admission_id }}) AS sample_count
FROM {{ main_table }}
WHERE {{ fields.hospital_id }} = :hospital_id
  AND {{ fields.admit_time }} >= :start_time
  AND {{ fields.admit_time }} < :end_time""",
    },
    "MQSI2025_005": {
        "index_name": "急会诊及时到位率",
        "index_type": "会诊制度",
        "numerator_rule": "急会诊请求发出后0至10分钟内到位的急会诊次数",
        "denominator_rule": "同期急会诊总次数",
        "filter_rule": "会诊类型为急会诊，按请求时间纳入统计周期",
        "exclude_rule": "无",
        "rule_params": {"arrive_minutes_threshold": 10, "consult_type_value": "急会诊"},
        "main_table": "consult_record",
        "fields": {
            "hospital_id": ("hospital_id", "string"),
            "patient_id": ("patient_id", "string"),
            "consult_type": ("consult_type", "string"),
            "request_time": ("request_time", "datetime"),
            "arrive_time": ("arrive_time", "datetime"),
            "dept_id": ("dept_id", "string"),
        },
        "standard_sql": """SELECT
  CASE WHEN COUNT(*) = 0 THEN 0
       ELSE ROUND(SUM(CASE
         WHEN TIMESTAMPDIFF(MINUTE, {{ fields.request_time }}, {{ fields.arrive_time }}) BETWEEN 0 AND :arrive_minutes_threshold
         THEN 1 ELSE 0 END) / COUNT(*) * 100, 2)
  END AS index_value,
  SUM(CASE
    WHEN TIMESTAMPDIFF(MINUTE, {{ fields.request_time }}, {{ fields.arrive_time }}) BETWEEN 0 AND :arrive_minutes_threshold
    THEN 1 ELSE 0 END) AS numerator_count,
  COUNT(*) AS denominator_count,
  COUNT(*) AS sample_count
FROM {{ main_table }}
WHERE {{ fields.hospital_id }} = :hospital_id
  AND {{ fields.consult_type }} = :consult_type_value
  AND {{ fields.request_time }} >= :start_time
  AND {{ fields.request_time }} < :end_time
{% for dept in custom_rules.exclude_dept_filters %}
  AND {{ fields.get('dept_id', 'dept_id') }} <> :{{ dept.param }}
{% endfor %}""",
    },
    "MQSI2025_014": {
        "index_name": "急危重症患者抢救成功率",
        "index_type": "急危重症抢救制度",
        "numerator_rule": "急危重症患者抢救结果为成功的抢救例次",
        "denominator_rule": "同期急危重症患者抢救总例次",
        "filter_rule": "患者严重程度为急危重症，按抢救时间纳入统计周期",
        "exclude_rule": "无",
        "rule_params": {"severity_value": "急危重症", "success_value": "成功"},
        "main_table": "critical_rescue_record",
        "fields": {
            "hospital_id": ("hospital_id", "string"),
            "patient_id": ("patient_id", "string"),
            "rescue_id": ("rescue_id", "string"),
            "rescue_time": ("rescue_time", "datetime"),
            "severity_level": ("severity_level", "string"),
            "rescue_result": ("rescue_result", "string"),
            "dept_id": ("dept_id", "string"),
        },
        "standard_sql": """SELECT
  CASE WHEN COUNT(*) = 0 THEN 0
       ELSE ROUND(SUM(CASE WHEN {{ fields.rescue_result }} = :success_value THEN 1 ELSE 0 END) / COUNT(*) * 100, 2)
  END AS index_value,
  SUM(CASE WHEN {{ fields.rescue_result }} = :success_value THEN 1 ELSE 0 END) AS numerator_count,
  COUNT(*) AS denominator_count,
  COUNT(*) AS sample_count
FROM {{ main_table }}
WHERE {{ fields.hospital_id }} = :hospital_id
  AND {{ fields.severity_level }} = :severity_value
  AND {{ fields.rescue_time }} >= :start_time
  AND {{ fields.rescue_time }} < :end_time""",
    },
    "MQSI2025_035": {
        "index_name": "术中自体血回输率",
        "index_type": "临床用血管理制度",
        "numerator_rule": "术中使用自体血回输的患者数量（按患者去重）",
        "denominator_rule": "同期术中进行输血的患者总数量（按患者去重）",
        "filter_rule": "术中输血标志为1，按手术时间纳入统计周期",
        "exclude_rule": "无",
        "rule_params": {"transfusion_flag_value": 1, "autologous_flag_value": 1},
        "main_table": "intraoperative_transfusion_record",
        "fields": {
            "hospital_id": ("hospital_id", "string"),
            "patient_id": ("patient_id", "string"),
            "surgery_id": ("surgery_id", "string"),
            "surgery_time": ("surgery_time", "datetime"),
            "intraoperative_transfusion_flag": ("intraoperative_transfusion_flag", "integer"),
            "autologous_reinfusion_flag": ("autologous_reinfusion_flag", "integer"),
            "dept_id": ("dept_id", "string"),
        },
        "standard_sql": """SELECT
  CASE WHEN COUNT(DISTINCT {{ fields.patient_id }}) = 0 THEN 0
       ELSE ROUND(COUNT(DISTINCT CASE
         WHEN {{ fields.autologous_reinfusion_flag }} = :autologous_flag_value
         THEN {{ fields.patient_id }} END) / COUNT(DISTINCT {{ fields.patient_id }}) * 100, 2)
  END AS index_value,
  COUNT(DISTINCT CASE
    WHEN {{ fields.autologous_reinfusion_flag }} = :autologous_flag_value
    THEN {{ fields.patient_id }} END) AS numerator_count,
  COUNT(DISTINCT {{ fields.patient_id }}) AS denominator_count,
  COUNT(DISTINCT {{ fields.patient_id }}) AS sample_count
FROM {{ main_table }}
WHERE {{ fields.hospital_id }} = :hospital_id
  AND {{ fields.intraoperative_transfusion_flag }} = :transfusion_flag_value
  AND {{ fields.surgery_time }} >= :start_time
  AND {{ fields.surgery_time }} < :end_time""",
    },
}


def _section(markdown: str, title: str) -> str:
    match = re.search(
        rf"^## {re.escape(title)}\s*\n(?P<body>.*?)(?=^## |\Z)",
        markdown,
        re.MULTILINE | re.DOTALL,
    )
    return match.group("body").strip() if match else ""


def _wiki_definition(kb_root: Path, index_code: str, fallback: str) -> tuple[str, str]:
    candidates = list((kb_root / "wiki" / "standards" / "national").glob(f"{index_code}_*.md"))
    if not candidates:
        return fallback, ""
    path = candidates[0]
    markdown = path.read_text(encoding="utf-8")
    definition = _section(markdown, "指标定义") or fallback
    return definition, path.relative_to(kb_root).as_posix()


def build_indicator_seeds(kb_root: Path) -> list[dict[str, Any]]:
    seeds: list[dict[str, Any]] = []
    for index_code in FOUR_INDICATOR_CODES:
        seed = {"index_code": index_code, **_SEEDS[index_code]}
        definition, source_path = _wiki_definition(kb_root, index_code, seed["index_name"])
        seed["index_desc"] = definition
        seed["source_path"] = source_path
        spec = load_rule_sql_spec(kb_root, index_code)
        field_contract = load_field_contract(kb_root, index_code)
        calculation = parse_calculation_definition(spec.get("calculation"))
        validation_params = {
            "hospital_id": "hospital_001",
            "start_time": "2000-01-01 00:00:00",
            "end_time": "2000-02-01 00:00:00",
            **seed["rule_params"],
        }
        errors = validate_calculation_definition(
            calculation,
            field_contract.get("business_fields") or {},
            validation_params,
        )
        if errors:
            raise ValueError(f"{index_code} 结构化计算定义无效：{'；'.join(errors)}")
        seed["field_contract"] = field_contract
        seed["calculation_definition"] = calculation.model_dump(mode="json")
        seeds.append(seed)
    return seeds


def _existing(conn: Connection, table_name: str, where: str, params: dict[str, Any]) -> bool:
    row = conn.execute(
        text(f"SELECT 1 FROM {table_name} WHERE {where} LIMIT 1"), params
    ).first()
    return row is not None


def _upsert_standard(conn: Connection, seed: dict[str, Any], now: str) -> str:
    exists = _existing(
        conn, "med_index_standard", "index_code=:index_code", {"index_code": seed["index_code"]}
    )
    params = {
        "index_code": seed["index_code"],
        "index_name": seed["index_name"],
        "index_type": seed["index_type"],
        "index_desc": seed["index_desc"],
        "numerator_rule": seed["numerator_rule"],
        "denominator_rule": seed["denominator_rule"],
        "filter_rule": seed["filter_rule"],
        "exclude_rule": seed["exclude_rule"],
        "rely_table_field": json.dumps(seed["field_contract"], ensure_ascii=False),
        "calculation_definition": json.dumps(
            seed["calculation_definition"], ensure_ascii=False
        ),
        "standard_sql": seed["standard_sql"],
        "rule_params": json.dumps(seed["rule_params"], ensure_ascii=False),
        "source_path": seed["source_path"],
        "now": now,
    }
    if exists:
        conn.execute(
            text(
                """
                UPDATE med_index_standard
                SET index_name=:index_name, index_type=:index_type, index_desc=:index_desc,
                    stat_cycle='month', numerator_rule=:numerator_rule,
                    denominator_rule=:denominator_rule, filter_rule=:filter_rule,
                    exclude_rule=:exclude_rule, rely_table_field=:rely_table_field,
                    calculation_definition=:calculation_definition,
                    standard_sql=:standard_sql, rule_params=:rule_params,
                    source_path=:source_path, version='2025', status=1, update_time=:now
                WHERE index_code=:index_code
                """
            ),
            params,
        )
        return "updated"
    conn.execute(
        text(
            """
            INSERT INTO med_index_standard
              (index_code, index_name, index_type, index_desc, stat_cycle,
               numerator_rule, denominator_rule, filter_rule, exclude_rule,
               rely_table_field, calculation_definition, standard_sql,
               rule_params, source_path,
               version, status, create_time, update_time)
            VALUES
              (:index_code, :index_name, :index_type, :index_desc, 'month',
               :numerator_rule, :denominator_rule, :filter_rule, :exclude_rule,
               :rely_table_field, :calculation_definition, :standard_sql,
               :rule_params, :source_path,
               '2025', 1, :now, :now)
            """
        ),
        params,
    )
    return "inserted"


def _upsert_field_mappings(
    conn: Connection, hospital_id: str, seed: dict[str, Any], now: str
) -> None:
    for business_field, (column_name, data_type) in seed["fields"].items():
        params = {
            "hospital_id": hospital_id,
            "rule_id": seed["index_code"],
            "business_field": business_field,
            "db_name": "hospital_demo_data",
            "table_name": seed["main_table"],
            "column_name": column_name,
            "data_type": data_type,
            "now": now,
        }
        where = (
            "hospital_id=:hospital_id AND rule_id=:rule_id "
            "AND business_field=:business_field"
        )
        if _existing(conn, "med_field_mapping", where, params):
            conn.execute(
                text(
                    f"""
                    UPDATE med_field_mapping
                    SET db_name=:db_name, table_name=:table_name,
                        column_name=:column_name, data_type=:data_type,
                        status='confirmed', updated_by='rule_import', updated_at=:now
                    WHERE {where}
                    """
                ),
                params,
            )
        else:
            conn.execute(
                text(
                    """
                    INSERT INTO med_field_mapping
                      (hospital_id, rule_id, business_field, db_name, table_name,
                       column_name, data_type, status, updated_by, updated_at)
                    VALUES
                      (:hospital_id, :rule_id, :business_field, :db_name, :table_name,
                       :column_name, :data_type, 'confirmed', 'rule_import', :now)
                    """
                ),
                params,
            )


def _insert_initial_custom(
    conn: Connection, hospital_id: str, seed: dict[str, Any], now: str
) -> None:
    lookup = {"hospital_id": hospital_id, "index_code": seed["index_code"]}
    if _existing(
        conn,
        "med_index_hospital_custom",
        "hospital_id=:hospital_id AND index_code=:index_code",
        lookup,
    ):
        return
    custom_params = {"arrive_minutes_threshold": 20}
    conn.execute(
        text(
            """
            INSERT INTO med_index_hospital_custom
              (hospital_id, index_code, custom_numerator, custom_denominator,
               custom_filter, exclude_rule, custom_params, custom_sql, version,
               status, approval_status, effective_from, effective_to, oper_user,
               create_time, update_time)
            VALUES
              (:hospital_id, :index_code, :custom_numerator, NULL, NULL, NULL,
               :custom_params, NULL, 1, 1, 'approved', NULL, NULL, 'rule_import',
               :now, :now)
            """
        ),
        {
            **lookup,
            "custom_numerator": "急会诊请求发出后0至20分钟内到位的急会诊次数",
            "custom_params": json.dumps(custom_params, ensure_ascii=False),
            "now": now,
        },
    )
    snapshot = {
        "custom_numerator": "急会诊请求发出后0至20分钟内到位的急会诊次数",
        "custom_denominator": None,
        "custom_filter": None,
        "exclude_rule": None,
        "custom_params": custom_params,
        "custom_calculation_patch": None,
        "custom_sql": None,
        "status": 1,
        "effective_from": None,
        "effective_to": None,
    }
    conn.execute(
        text(
            """
            INSERT INTO med_index_hospital_custom_version
              (change_id, hospital_id, index_code, version, approval_status,
               snapshot_json, source_version, change_type, oper_user, approver_id,
               created_at, approved_at)
            VALUES
              (:change_id, :hospital_id, :index_code, 1, 'approved', :snapshot_json,
               NULL, 'initial_import', 'rule_import', 'rule_import', :now, :now)
            """
        ),
        {
            **lookup,
            "change_id": f"SEED_{hospital_id}_{seed['index_code']}_V1",
            "snapshot_json": json.dumps(snapshot, ensure_ascii=False),
            "now": now,
        },
    )


def import_four_indicator_rules(
    engine: Engine, kb_root: Path, hospital_id: str = "hospital_001"
) -> dict[str, Any]:
    ensure_rule_lineage_schema(engine)
    result: dict[str, Any] = {"inserted": [], "updated": [], "failed": []}
    for seed in build_indicator_seeds(Path(kb_root)):
        try:
            now = datetime.now().isoformat(sep=" ", timespec="seconds")
            with engine.begin() as conn:
                action = _upsert_standard(conn, seed, now)
                _upsert_field_mappings(conn, hospital_id, seed, now)
                if seed["index_code"] == "MQSI2025_005":
                    _insert_initial_custom(conn, hospital_id, seed, now)
            result[action].append(seed["index_code"])
        except Exception as exc:
            result["failed"].append(
                {"index_code": seed["index_code"], "error": str(exc)}
            )
    return result

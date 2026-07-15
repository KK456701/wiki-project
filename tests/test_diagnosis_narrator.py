from app.diagnose.narrator import DiagnosisNarrator


DIAGNOSIS = {
    "primary_conclusion": "caliber_difference",
    "findings": [
        {
            "code": "period_field_changed",
            "category": "caliber",
            "severity": "warning",
            "title": "统计范围使用的时间字段不同",
            "evidence": "系统使用 ADMITTED_AT；用户 SQL 使用 FIRST_ADMITTED_TO_WARD_AT。",
            "impact": "两段 SQL 纳入统计的患者批次可能不同，分母会直接变化。",
            "suggestion": "请业务确认统计周期应按入院时间还是首次入区时间。",
        },
        {
            "code": "upper_boundary_inclusive_changed",
            "category": "caliber",
            "severity": "warning",
            "title": "48 小时边界不同",
            "evidence": "系统包含正好 48 小时；用户 SQL 不包含。",
            "impact": "边界记录只会被其中一段 SQL 计入。",
            "suggestion": "请在口径中明确是否包含正好 48 小时。",
        },
    ],
    "execution_results": {
        "user": {
            "status": "success",
            "result_value": 1.27,
            "numerator_count": 2,
            "denominator_count": 158,
        },
        "national": {
            "status": "success",
            "result_value": 2.5,
            "numerator_count": 4,
            "denominator_count": 160,
        },
        "hospital": {
            "status": "success",
            "result_value": 3.0,
            "numerator_count": 5,
            "denominator_count": 166,
        },
    },
    "comparison_rows": [
        {
            "item": "统计范围使用的时间字段不同",
            "user_sql": "FIRST_ADMITTED_TO_WARD_AT（首次入区时间）",
            "current_sql": "ADMITTED_AT（入院时间）",
            "impact": "两段 SQL 纳入统计的患者批次可能不同，分母会直接变化。",
            "suggestion": "请业务确认统计周期应按入院时间还是首次入区时间。",
        },
        {
            "item": "48 小时边界不同",
            "user_sql": "不包含正好 48 小时",
            "current_sql": "包含正好 48 小时",
            "impact": "边界记录只会被其中一段 SQL 计入。",
            "suggestion": "请在口径中明确是否包含正好 48 小时。",
        },
    ],
    "effective_source": {
        "label": "本院生效口径 v1",
        "national_version": "2025",
        "overridden_fields": ["admission_time_basis"],
    },
    "evidence": {
        "raw_text": "患者 PC-123 的明细不应进入模型",
        "sql_text": "SELECT * FROM WINDBA.PATIENT",
        "question": "为什么不一样？",
        "rule_id": "MQSI2025_001",
        "declared_params": {"BeginAt": "2026-06-01"},
        "claimed_result": {},
        "stat_period": {"start": "2026-06-01", "end": "2026-08-01"},
    },
}


class _FakeLLM:
    def __init__(self, response):
        self.response = response
        self.prompts = []

    def generate(self, prompt):
        self.prompts.append(prompt)
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


def test_accepts_model_wording_when_it_preserves_verified_conclusion():
    answer = """## 结论
两段 SQL 都能执行，结果不同主要是统计口径差异。

## SQL 试运行结果
| 计算方式 | 执行状态 | 分子 | 分母 | 指标结果 |
|---|---|---:|---:|---:|
| 用户 SQL | 执行成功 | 2 | 158 | 1.27 |
| 当前生效 SQL | 执行成功 | 5 | 166 | 3.0 |

## 计算规则差异
| 比较项目 | 用户 SQL | 当前生效 SQL | 对结果的影响 | 建议 |
|---|---|---|---|---|
| 统计范围使用的时间字段不同 | FIRST_ADMITTED_TO_WARD_AT | ADMITTED_AT | 分母可能变化 | 请确认时间口径 |
| 48 小时边界不同 | 不包含 | 包含 | 分子可能变化 | 请确认边界 |

## 建议怎么处理
请确认按入院时间还是首次入区时间，并明确是否包含正好 48 小时。
"""
    llm = _FakeLLM(answer)

    result = DiagnosisNarrator(llm).compose(DIAGNOSIS)

    assert result == answer.strip()
    assert "PC-123" not in llm.prompts[0]
    assert "SELECT * FROM" not in llm.prompts[0]
    assert '"national"' not in llm.prompts[0]


def test_rejects_model_answer_that_changes_caliber_conclusion():
    llm = _FakeLLM("结论：数据库连接故障，请重启数据库。")

    answer = DiagnosisNarrator(llm).compose(DIAGNOSIS)

    assert "口径" in answer
    assert "数据库连接故障" not in answer
    assert "统计范围使用的时间字段不同" in answer
    assert "第一层" not in answer


def test_falls_back_when_model_is_unavailable():
    answer = DiagnosisNarrator(_FakeLLM(RuntimeError("ollama timeout"))).compose(
        DIAGNOSIS
    )

    assert answer.startswith("## 结论")
    assert "2 / 158" in answer
    assert "5 / 166" in answer
    assert "## SQL 试运行结果" in answer
    assert "| 用户 SQL |" in answer
    assert "| 当前生效 SQL |" in answer
    assert "| 国标口径 |" not in answer
    assert "## 计算规则差异" in answer
    assert "FIRST_ADMITTED_TO_WARD_AT（首次入区时间）" in answer
    assert "ADMITTED_AT（入院时间）" in answer
    assert "当前生效 SQL 来源：本院生效口径 v1" in answer
    assert "建议怎么处理" in answer


def test_national_reference_failure_does_not_replace_user_vs_current_conclusion():
    diagnosis = {
        **DIAGNOSIS,
        "execution_results": {
            **DIAGNOSIS["execution_results"],
            "national": {
                "status": "failed",
                "error_code": "sql_validation_failed",
                "error_message": "参考 SQL 语法错误",
            },
        },
    }

    answer = DiagnosisNarrator(None).compose(diagnosis)

    conclusion = answer.split("## SQL 试运行结果", 1)[0]
    assert "用户 SQL 为 1.27%" in conclusion
    assert "当前生效 SQL 为 3.0%" in conclusion
    assert "无法比较" not in conclusion
    assert "国标" not in conclusion
    assert "国标参考 SQL：执行失败" in answer


def test_blocked_sql_is_explained_without_calling_it_database_failure():
    diagnosis = {
        **DIAGNOSIS,
        "primary_conclusion": "user_sql_blocked",
        "findings": [],
        "execution_results": {
            **DIAGNOSIS["execution_results"],
            "user": {
                "status": "blocked",
                "blocked_reasons": ["SQL 包含写入语句。"],
            },
        },
    }

    answer = DiagnosisNarrator(None).compose(diagnosis)

    assert "没有执行" in answer
    assert "SQL 包含写入语句" in answer
    assert "数据库故障" not in answer

import json

from app.observability.workflow_nodes import record_diagnose_trace_nodes


class _Recorder:
    def __init__(self):
        self.calls = []

    def record_node(self, trace_id, node_name, node_type, status, **kwargs):
        self.calls.append({
            "trace_id": trace_id,
            "node_name": node_name,
            "node_type": node_type,
            "status": status,
            **kwargs,
        })


def test_records_pasted_diagnosis_events_and_removes_sensitive_payloads():
    recorder = _Recorder()
    result = {
        "trace_events": [
            {
                "node_name": "evidence_extract",
                "status": "success",
                "duration_ms": 4,
                "output_summary": "已识别 SQL 和 3 个参数",
                "input_data": {
                    "raw_text": "患者 PC-001",
                    "sql_text": "SELECT * FROM patient",
                },
                "output_data": {"parameter_names": ["BeginAt"]},
            },
            {
                "node_name": "user_sql_guard",
                "status": "warning",
                "duration_ms": 2,
                "output_summary": "未执行，已完成静态分析",
                "output_data": {
                    "blocked_reasons": ["SQL 包含写操作"],
                    "rows": [{"patient_id": "PC-001"}],
                },
            },
        ]
    }

    record_diagnose_trace_nodes(
        recorder,
        "TRACE_001",
        result,
        "MQSI2025_001",
        "hospital_001",
    )

    assert [item["node_name"] for item in recorder.calls] == [
        "evidence_extract",
        "user_sql_guard",
    ]
    serialized = json.dumps(recorder.calls, ensure_ascii=False)
    assert "SELECT * FROM patient" not in serialized
    assert "PC-001" not in serialized
    assert recorder.calls[1]["status"] == "warning"

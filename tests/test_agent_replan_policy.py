from app.agent_planning.replan import ReplanPolicy
from app.agent_runtime.contracts import AgentRunState


def test_missing_parameter_does_not_trigger_replan():
    policy = ReplanPolicy(max_replan_count=1)

    assert policy.can_replan(AgentRunState(), "INVALID_TOOL_ARGUMENTS") is False


def test_plan_direction_failure_can_replan_only_once():
    policy = ReplanPolicy(max_replan_count=1)
    state = AgentRunState()

    assert policy.can_replan(state, "PLAN_INTENT_MISMATCH") is True
    policy.record_failure(state, "PLAN_A")

    assert state.replan_count == 1
    assert state.failed_plan_fingerprints == ["PLAN_A"]
    assert policy.can_replan(state, "PLAN_INTENT_MISMATCH") is False


def test_replanned_plan_cannot_repeat_failed_fingerprint():
    policy = ReplanPolicy(max_replan_count=2)
    state = AgentRunState(failed_plan_fingerprints=["PLAN_A"])

    assert policy.accept_plan(state, "PLAN_A") is False
    assert policy.accept_plan(state, "PLAN_B") is True


def test_database_outage_is_operator_fallback_not_replan():
    policy = ReplanPolicy(max_replan_count=1)

    assert policy.can_replan(AgentRunState(), "DATABASE_UNAVAILABLE") is False
    assert policy.fallback_for("DATABASE_UNAVAILABLE").value == "SYSTEM_OPERATOR"


def test_diagnosis_tool_failure_does_not_trigger_replan():
    policy = ReplanPolicy(max_replan_count=1)

    assert policy.can_replan(AgentRunState(), "DIAGNOSIS_FAILED") is False


def test_unauthorized_patient_detail_is_security_denial():
    policy = ReplanPolicy(max_replan_count=1)

    assert policy.fallback_for("PATIENT_DETAIL_FORBIDDEN").value == "SECURITY_DENIAL"

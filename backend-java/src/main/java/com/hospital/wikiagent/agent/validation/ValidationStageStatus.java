package com.hospital.wikiagent.agent.validation;

/**
 * 枚举 {@code ValidationStageStatus} 允许的有限业务状态。
 *
 * <p>有限状态用于编译期约束 Planner、Controller 和 Verifier 的分支。未知文本必须被拒绝或进入明确兜底，不能静默映射为成功状态。</p>
 */
public enum ValidationStageStatus {
    PASSED("passed"),
    WARNING("warning"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String value;

    ValidationStageStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

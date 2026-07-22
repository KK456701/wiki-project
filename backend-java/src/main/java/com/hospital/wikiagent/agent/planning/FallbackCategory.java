package com.hospital.wikiagent.agent.planning;

/**
 * 枚举 {@code FallbackCategory} 允许的有限业务状态。
 *
 * <p>有限状态用于编译期约束 Planner、Controller 和 Verifier 的分支。未知文本必须被拒绝或进入明确兜底，不能静默映射为成功状态。</p>
 */
public enum FallbackCategory {
    USER_CLARIFICATION,
    BUSINESS_CONFIRMATION,
    ADMIN_APPROVAL,
    IMPLEMENTATION_SUPPORT,
    SYSTEM_OPERATOR,
    SECURITY_DENIAL
}

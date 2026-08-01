package com.hospital.wikiagent.agent.mras;

/**
 * 定义后端允许返回的五种指标明细展示与对账契约。
 *
 * <p>枚举值同时作为批次持久化字段和前端组件选择依据；新增类型必须先补充服务端
 * 对账规则、运行绑定和专用展示，不能回退成普通分子/分母，也不能由模型修改。</p>
 */
public enum MrasDetailKind {
    COUNT_RATIO,
    SUM_CONTRIBUTION,
    MEDIAN_SAMPLE,
    DUAL_SOURCE,
    RATE_COMPARISON
}

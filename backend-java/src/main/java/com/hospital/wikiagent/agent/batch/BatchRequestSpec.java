package com.hospital.wikiagent.agent.batch;

/**
 * 描述一次请求是否应进入批量指标计算路径的确定性识别结果。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。识别完全由
 * 确定性正则给出，不依赖小模型判断，避免 7B 模型在批量意图识别上不稳定。</p>
 */
public record BatchRequestSpec(
        boolean batch,
        boolean allActive,
        String rawQuery) {

    public BatchRequestSpec {
        rawQuery = rawQuery == null ? "" : rawQuery.strip();
    }

    /** 非批量请求：继续走原有单指标/复合路径。 */
    public static BatchRequestSpec notBatch() {
        return new BatchRequestSpec(false, false, "");
    }

    /** 批量请求（计算全部活跃指标）。 */
    public static BatchRequestSpec allActive(String rawQuery) {
        return new BatchRequestSpec(true, true, rawQuery);
    }
}

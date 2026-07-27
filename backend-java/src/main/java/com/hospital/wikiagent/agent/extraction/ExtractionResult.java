package com.hospital.wikiagent.agent.extraction;

import java.time.Instant;

/**
 * 承载抽取接口返回的安全、不可变执行摘要。
 *
 * <p>对象只记录抽取 ID、行数、快照引用和安全错误，不包含患者级原始数据、连接串
 * 或 SQL 执行明细。双库 Workflow 仅在 {@link #successful()} 为真时继续查询，
 * 失败结果不得被缓存成可复用的成功回执。</p>
 */
public record ExtractionResult(
        String extractionId,
        Status status,
        long extractedRows,
        long insertedRows,
        long updatedRows,
        long rejectedRows,
        Instant completedAt,
        String sourceSnapshotId,
        String targetSnapshotId,
        String errorCode,
        String message) {

    public boolean successful() {
        return status == Status.SUCCESS;
    }

    /**
     * 判断双库只读计算能否继续。
     *
     * <p>{@link Status#SKIPPED_DISABLED} 只表示部署尚未接入抽取接口，运行时按配置跳过了抽取；
     * 它不会伪装成一次成功抽取，但允许业务库和真实库继续进行只读核对。
     * 强制抽取模式仍然只接受 {@link Status#SUCCESS}。</p>
     */
    public boolean allowsDualExecution() {
        return status == Status.SUCCESS || status == Status.SKIPPED_DISABLED;
    }

    public enum Status {
        SUCCESS,
        SKIPPED_DISABLED,
        FAILED
    }
}

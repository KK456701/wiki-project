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

    public enum Status {
        SUCCESS,
        FAILED
    }
}

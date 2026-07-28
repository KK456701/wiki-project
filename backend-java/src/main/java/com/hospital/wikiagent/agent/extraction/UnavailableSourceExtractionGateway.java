package com.hospital.wikiagent.agent.extraction;

import java.time.Instant;

/**
 * 受控抽取未启用或写入连接未配置时使用的安全占位实现。
 *
 * <p>该实现绝不伪造抽取成功；强制模式会在调用前识别其不可用状态并停止。
 * 它只由条件配置在没有真实网关 Bean 时创建，也不会把 disabled 模式误报为已经
 * 完成真实库写入。</p>
 */
public class UnavailableSourceExtractionGateway implements SourceExtractionGateway {
    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        return new ExtractionResult(
                "",
                ExtractionResult.Status.FAILED,
                0, 0, 0, 0,
                Instant.now(),
                "", "",
                "EXTRACTION_GATEWAY_UNAVAILABLE",
                "源数据抽取接口尚未接入。");
    }

    @Override
    public SourceExtractionLease prepare(ExtractionRequest request) {
        return SourceExtractionLease.completed(extract(request));
    }

    @Override
    public boolean available() {
        return false;
    }
}

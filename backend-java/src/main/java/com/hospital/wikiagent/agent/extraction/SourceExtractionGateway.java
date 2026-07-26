package com.hospital.wikiagent.agent.extraction;

/**
 * 医院源数据抽取能力的内部边界。
 *
 * <p>实现方负责调用外部抽取接口并把源 SQL 对应数据写入真实库。Agent、DBHub 和
 * 本接口之外的 Java 代码均不得直接写医院数据库。</p>
 */
public interface SourceExtractionGateway {
    ExtractionResult extract(ExtractionRequest request);

    /**
     * 默认占位网关返回 {@code false}，强制模式据此在执行前快速失败。
     */
    default boolean available() {
        return true;
    }
}

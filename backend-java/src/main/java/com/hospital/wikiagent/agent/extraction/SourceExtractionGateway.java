package com.hospital.wikiagent.agent.extraction;

/**
 * 医院源数据抽取能力的内部边界。
 *
 * <p>当前实现从不可变知识发布包取得受控 SQL 或固定表域契约，通过本机 DBHub
 * 只读查询业务库，再由最小权限 JDBC 连接原子替换真实库。Agent、DBHub 和本接口
 * 之外的 Java 代码均不得直接写医院数据库。</p>
 */
public interface SourceExtractionGateway {
    ExtractionResult extract(ExtractionRequest request);

    /**
     * 准备抽取租约（调用方可通过 try-with-resources 管理生命周期）。
     */
    SourceExtractionLease prepare(ExtractionRequest request);

    /**
     * 默认占位网关返回 {@code false}，强制模式据此在执行前快速失败。
     */
    default boolean available() {
        return true;
    }
}

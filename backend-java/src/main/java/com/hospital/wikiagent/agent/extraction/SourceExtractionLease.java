package com.hospital.wikiagent.agent.extraction;

import java.util.Objects;

/**
 * 在真实库快照被消费完之前保持抽取侧互斥锁。
 *
 * <p>Workflow 必须以 try-with-resources 使用本对象，确保成功、失败和提前返回都释放
 * SQL Server Session application lock，避免后续 Profile 永久等待。</p>
 */
public final class SourceExtractionLease implements AutoCloseable {
    private final ExtractionResult result;
    private final Runnable closer;
    private boolean closed;

    public SourceExtractionLease(ExtractionResult result, Runnable closer) {
        this.result = Objects.requireNonNull(result, "result");
        this.closer = closer == null ? () -> { } : closer;
    }

    public static SourceExtractionLease completed(ExtractionResult result) {
        return new SourceExtractionLease(result, null);
    }

    public ExtractionResult result() {
        return result;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closer.run();
        }
    }
}

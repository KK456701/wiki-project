package com.hospital.wikiagent.contract;

/**
 * 定义 {@code UploadResponse} 的不可变数据载体。
 */
public record UploadResponse(String fileKey, String fileName, long sizeBytes) {
}

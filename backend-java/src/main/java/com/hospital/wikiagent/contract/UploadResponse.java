package com.hospital.wikiagent.contract;

public record UploadResponse(String fileKey, String fileName, long sizeBytes) {
}

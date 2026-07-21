package com.hospital.wikiagent.upload;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wiki.agent.upload")
public class UploadProperties {
    private Path root = Path.of("runtime", "uploads");
    private long maxBytes = 10L * 1024L * 1024L;
    private long maxUncompressedBytes = 50L * 1024L * 1024L;
    private int maxEntries = 2_000;
    private int maxRowsPerSheet = 5_001;
    private int maxColumns = 100;

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public long getMaxUncompressedBytes() {
        return maxUncompressedBytes;
    }

    public void setMaxUncompressedBytes(long maxUncompressedBytes) {
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public int getMaxRowsPerSheet() {
        return maxRowsPerSheet;
    }

    public void setMaxRowsPerSheet(int maxRowsPerSheet) {
        this.maxRowsPerSheet = maxRowsPerSheet;
    }

    public int getMaxColumns() {
        return maxColumns;
    }

    public void setMaxColumns(int maxColumns) {
        this.maxColumns = maxColumns;
    }
}

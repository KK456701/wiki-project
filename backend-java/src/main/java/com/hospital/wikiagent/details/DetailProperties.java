package com.hospital.wikiagent.details;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wiki.agent.details")
/**
 * 承载 {@code DetailProperties} 对应的类型化配置，避免业务代码直接读取环境变量。
 */
public class DetailProperties {
    private Path exportRoot = Path.of("runtime", "exports");
    private int expireHours = 24;
    private int maxRows = 20_000;
    private int defaultPageSize = 50;

    public Path getExportRoot() {
        return exportRoot;
    }

    public void setExportRoot(Path exportRoot) {
        this.exportRoot = exportRoot;
    }

    public int getExpireHours() {
        return expireHours;
    }

    public void setExpireHours(int expireHours) {
        this.expireHours = expireHours;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }
}

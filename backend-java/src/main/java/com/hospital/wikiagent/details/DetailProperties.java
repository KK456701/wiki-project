package com.hospital.wikiagent.details;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 {@code DetailProperties} 对应的类型化配置，避免业务代码直接读取环境变量。
 *
 * <p>配置由 Spring Boot 在启动阶段完成类型化绑定；缺失的安全关键值必须显式失败或保持安全默认值。业务代码不得再次从环境变量读取同一配置。</p>
 */
@ConfigurationProperties(prefix = "wiki.agent.details")
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

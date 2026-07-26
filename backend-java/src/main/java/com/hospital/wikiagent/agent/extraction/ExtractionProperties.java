package com.hospital.wikiagent.agent.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制源数据抽取是否成为指标计算的强制前置步骤。
 *
 * <p>当前仓库只定义网关契约，不包含医院抽取接口适配器。默认关闭时保持现有单库
 * 行为；部署方只有在提供真实 {@link SourceExtractionGateway} 后才能切换为
 * {@link Mode#REQUIRED}。</p>
 */
@ConfigurationProperties(prefix = "wiki.agent.extraction")
public class ExtractionProperties {
    private Mode mode = Mode.DISABLED;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DISABLED : mode;
    }

    public boolean required() {
        return mode == Mode.REQUIRED;
    }

    public enum Mode {
        DISABLED,
        REQUIRED
    }
}

package com.hospital.wikiagent.agent.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制源数据抽取是否成为指标计算的强制前置步骤。
 *
 * <p>当前仓库只定义网关契约，不包含医院抽取接口适配器。该开关只控制“抽取是否
 * 是双库计算的强制前置步骤”，不控制是否执行双库核对：默认关闭时跳过抽取，但
 * 业务库和真实库仍然都会执行；部署方提供真实 {@link SourceExtractionGateway}
 * 后可切换为 {@link Mode#REQUIRED}。</p>
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

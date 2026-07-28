package com.hospital.wikiagent.agent.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制源数据抽取是否成为指标计算的强制前置步骤。
 *
 * <p>{@link Mode#REQUIRED} 使用知识发布包中的受控 Profile 抽取契约，经本机
 * DBHub 读取业务库并原子替换 {@code winex_aima} 快照；普通计算只读取该快照。
 * {@link Mode#DISABLED} 只允许不访问数据库的定义、口径和 SQL 展示，计算会明确
 * 失败，不能静默使用旧快照。</p>
 */
@ConfigurationProperties(prefix = "wiki.agent.extraction")
public class ExtractionProperties {
    private Mode mode = Mode.DISABLED;
    private Long hospitalSoid;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DISABLED : mode;
    }

    public boolean required() {
        return mode == Mode.REQUIRED;
    }

    public Long getHospitalSoid() {
        return hospitalSoid;
    }

    public void setHospitalSoid(Long hospitalSoid) {
        this.hospitalSoid = hospitalSoid;
    }

    public enum Mode {
        DISABLED,
        REQUIRED
    }
}

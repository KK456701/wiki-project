package com.hospital.wikiagent.agent.sql;

/**
 * 枚举双库指标执行允许访问的两个固定逻辑角色。
 *
 * <p>业务代码只能传递该枚举，不能接受模型或浏览器提交任意 DBHub source ID。
 * 具体数据源和工具名由类型化配置解析，使业务库、真实库的 Evidence 和运行记录
 * 始终能够按角色隔离。</p>
 */
public enum DatabaseRole {
    BUSINESS("business"),
    REAL("real");

    private final String value;

    DatabaseRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

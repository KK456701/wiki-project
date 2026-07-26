package com.hospital.wikiagent.dbhub;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 在应用启动阶段拒绝旧的“单数据库”配置。
 *
 * <p>Spring Boot 对未知配置项默认不会报错。如果部署机器仍保留旧环境变量，静默忽略
 * 会让运维人员误以为系统仍在使用旧库。因此这里专门检查已经废弃的顶层配置入口，
 * 一旦发现就立即停止启动，并给出角色化配置迁移说明。</p>
 */
@Component
public class RetiredDatabaseConfigGuard {

    private static final List<String> RETIRED_KEYS = List.of(
            "wiki.dbhub.source-id",
            "wiki.dbhub.execute-tool",
            "wiki.dbhub.database-name",
            "wiki.dbhub.schema-name",
            "DBHUB_SOURCE_ID",
            "DBHUB_EXECUTE_TOOL",
            "DBHUB_DATABASE",
            "DBHUB_SCHEMA");

    private final Environment environment;

    public RetiredDatabaseConfigGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void rejectRetiredConfiguration() {
        for (String key : RETIRED_KEYS) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                throw new IllegalStateException(
                        "检测到已废弃的 DBHub 单库配置 " + key
                                + "。请删除该配置，改用 wiki.dbhub.sources.business"
                                + " 和 wiki.dbhub.sources.real。");
            }
        }
    }
}

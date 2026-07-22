package com.hospital.wikiagent.implementation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 初始化 {@code IndicatorDraftSchemaInitializer} 所需的运行结构，并保证重复启动时可安全执行。
 *
 * <p>初始化过程使用幂等 DDL，允许应用重复启动，但不会覆盖已有业务数据。这里只维护运行时结构，不读取或复制医院患者数据。</p>
 */
@Component
public class IndicatorDraftSchemaInitializer {
    private final JdbcTemplate jdbc;

    public IndicatorDraftSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_indicator_draft (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      draft_id VARCHAR(64) NOT NULL UNIQUE,
                      hospital_id VARCHAR(64) NOT NULL,
                      base_index_code VARCHAR(64), proposed_index_code VARCHAR(64) NOT NULL,
                      index_name VARCHAR(128) NOT NULL, index_type VARCHAR(64) NOT NULL,
                      index_desc TEXT NOT NULL, stat_cycle VARCHAR(32) NOT NULL,
                      numerator_rule TEXT NOT NULL, denominator_rule TEXT NOT NULL,
                      filter_rule TEXT, exclude_rule TEXT, metric_type VARCHAR(32) NOT NULL,
                      metadata_requirements JSON NOT NULL, field_mapping JSON NOT NULL,
                      sql_plan JSON NOT NULL, current_sql LONGTEXT, sql_params JSON NOT NULL,
                      sql_id VARCHAR(64), trial_result JSON NOT NULL, trial_draft_version INT,
                      status VARCHAR(32) NOT NULL, current_version INT NOT NULL,
                      formal_index_code VARCHAR(64), generated_by VARCHAR(64),
                      created_by VARCHAR(64) NOT NULL, updated_by VARCHAR(64) NOT NULL,
                      created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
                      UNIQUE KEY uk_draft_hospital_code (hospital_id, proposed_index_code),
                      INDEX idx_draft_hospital_status (hospital_id, status)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_indicator_draft_version (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      draft_id VARCHAR(64) NOT NULL, version INT NOT NULL,
                      status VARCHAR(32) NOT NULL, snapshot_json JSON NOT NULL,
                      change_type VARCHAR(64) NOT NULL, oper_user VARCHAR(64) NOT NULL,
                      created_at DATETIME NOT NULL,
                      UNIQUE KEY uk_draft_version (draft_id, version),
                      INDEX idx_draft_version_status (draft_id, status)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_index_hospital_defined_version (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      hospital_id VARCHAR(64) NOT NULL,index_code VARCHAR(64) NOT NULL,
                      version INT NOT NULL,snapshot_json JSON NOT NULL,source_version INT,
                      source_draft_id VARCHAR(64),change_type VARCHAR(64) NOT NULL,
                      oper_user VARCHAR(64) NOT NULL,approver_id VARCHAR(64),
                      created_at DATETIME NOT NULL,approved_at DATETIME,
                      UNIQUE KEY uk_hospital_defined_version (hospital_id,index_code,version)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_index_hospital_custom_version (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,change_id VARCHAR(64) NOT NULL,
                      hospital_id VARCHAR(64) NOT NULL,index_code VARCHAR(64) NOT NULL,
                      version INT NOT NULL,approval_status VARCHAR(32) NOT NULL,
                      snapshot_json JSON NOT NULL,source_version INT,change_type VARCHAR(64) NOT NULL,
                      oper_user VARCHAR(64),approver_id VARCHAR(64),created_at DATETIME NOT NULL,
                      approved_at DATETIME,UNIQUE KEY uk_custom_change (change_id),
                      UNIQUE KEY uk_hospital_index_version (hospital_id,index_code,version)
                    )
                    """);
        } catch (RuntimeException ignored) {
            // 共享运行库暂不可用时不阻止影子服务启动。
        }
    }
}

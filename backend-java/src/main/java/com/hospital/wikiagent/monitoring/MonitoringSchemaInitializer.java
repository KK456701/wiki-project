package com.hospital.wikiagent.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 复用 Python 监控表，并支持在空的 Java 运行库中初始化。 */
@Component
public class MonitoringSchemaInitializer {
    private final JdbcTemplate jdbc;

    public MonitoringSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_indicator_run_plan (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      plan_id VARCHAR(64) NOT NULL UNIQUE,
                      hospital_id VARCHAR(64) NOT NULL,
                      rule_id VARCHAR(64) NOT NULL,
                      plan_name VARCHAR(128) NOT NULL,
                      frequency VARCHAR(32) NOT NULL,
                      run_time VARCHAR(8) NOT NULL DEFAULT '02:00',
                      day_of_month INT NOT NULL DEFAULT 1,
                      timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
                      mom_enabled TINYINT NOT NULL DEFAULT 1,
                      mom_threshold_pct DECIMAL(18,4) NOT NULL DEFAULT 20,
                      yoy_enabled TINYINT NOT NULL DEFAULT 1,
                      yoy_threshold_pct DECIMAL(18,4) NOT NULL DEFAULT 30,
                      status VARCHAR(32) NOT NULL DEFAULT 'enabled',
                      next_run_at DATETIME, last_run_at DATETIME,
                      locked_until DATETIME, locked_by VARCHAR(128) NOT NULL DEFAULT '',
                      created_by VARCHAR(64) NOT NULL DEFAULT 'admin',
                      created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
                      UNIQUE KEY uq_monitor_plan (hospital_id,rule_id,plan_name),
                      INDEX idx_monitor_plan_status (status),
                      INDEX idx_monitor_plan_next (next_run_at)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_indicator_alert (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      alert_id VARCHAR(64) NOT NULL UNIQUE,
                      hospital_id VARCHAR(64) NOT NULL,
                      rule_id VARCHAR(64) NOT NULL,
                      plan_id VARCHAR(64), result_id BIGINT NOT NULL,
                      alert_type VARCHAR(32) NOT NULL, alert_level VARCHAR(16) NOT NULL,
                      conclusion_code VARCHAR(64) NOT NULL,
                      current_value DECIMAL(18,4), mom_value DECIMAL(18,4),
                      mom_change_rate DECIMAL(18,4), yoy_value DECIMAL(18,4),
                      yoy_change_rate DECIMAL(18,4),
                      diagnose_status VARCHAR(32) NOT NULL DEFAULT 'pending',
                      diagnose_report_id VARCHAR(64), status VARCHAR(32) NOT NULL DEFAULT 'open',
                      acknowledged_by VARCHAR(64), acknowledged_at DATETIME, closed_at DATETIME,
                      created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
                      UNIQUE KEY uq_monitor_alert (result_id,alert_type,conclusion_code),
                      INDEX idx_monitor_alert_hospital (hospital_id),
                      INDEX idx_monitor_alert_status (status)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_index_run_result (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      hospital_id VARCHAR(64) NOT NULL, rule_id VARCHAR(64) NOT NULL,
                      stat_period VARCHAR(128) NOT NULL, result_value DECIMAL(18,4),
                      previous_value DECIMAL(18,4), change_rate DECIMAL(18,4),
                      is_abnormal TINYINT NOT NULL DEFAULT 0, run_id VARCHAR(64),
                      created_at DATETIME NOT NULL, plan_id VARCHAR(64), run_key VARCHAR(255),
                      retry_of_result_id BIGINT, trigger_type VARCHAR(32),
                      stat_start_time DATETIME, stat_end_time DATETIME, run_status VARCHAR(32),
                      no_sample TINYINT NOT NULL DEFAULT 0, effective_level VARCHAR(32),
                      national_version VARCHAR(64), hospital_version INT, data_source VARCHAR(128),
                      duration_ms INT, error_code VARCHAR(128), error_message TEXT,
                      mom_baseline_result_id BIGINT, mom_change_rate DECIMAL(18,4),
                      yoy_baseline_result_id BIGINT, yoy_change_rate DECIMAL(18,4),
                      wave_status VARCHAR(64), UNIQUE KEY uq_med_index_run_result_run_key (run_key)
                    )
                    """);
        } catch (RuntimeException ignored) {
            // 共享运行库暂不可用时不阻止影子服务启动。
        }
    }
}

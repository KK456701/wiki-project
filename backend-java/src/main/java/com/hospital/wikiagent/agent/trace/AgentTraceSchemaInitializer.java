package com.hospital.wikiagent.agent.trace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 初始化 Java Trace 表；已有 SQLite 运行库会以兼容方式补齐缺失字段。
 *
 * <p>初始化过程使用幂等 DDL，允许应用重复启动，但不会覆盖已有业务数据。这里只维护运行时结构，不读取或复制医院患者数据。</p>
 */
@Component
public class AgentTraceSchemaInitializer {
    private final JdbcTemplate jdbc;

    public AgentTraceSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void initialize() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_trace (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      trace_id VARCHAR(64) NOT NULL UNIQUE,
                      session_id VARCHAR(128), hospital_id VARCHAR(64), user_id VARCHAR(128),
                      user_query TEXT, intent VARCHAR(64), final_status VARCHAR(32),
                      final_answer_summary TEXT, error_count INT DEFAULT 0,
                      fallback_count INT DEFAULT 0, started_at DATETIME NOT NULL,
                      ended_at DATETIME, duration_ms INT, created_at DATETIME NOT NULL,
                      INDEX idx_agent_trace_hospital_started (hospital_id,started_at)
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_agent_trace_node (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      trace_id VARCHAR(64) NOT NULL, node_id VARCHAR(64) NOT NULL,
                      node_name VARCHAR(128) NOT NULL, node_type VARCHAR(64) NOT NULL,
                      status VARCHAR(32) NOT NULL, input_summary TEXT, output_summary TEXT,
                      error_code VARCHAR(128), error_message TEXT, tool_name VARCHAR(128),
                      db_source VARCHAR(128), sql_id VARCHAR(64), run_id VARCHAR(64),
                      rule_id VARCHAR(64), llm_model VARCHAR(128), started_at DATETIME NOT NULL,
                      ended_at DATETIME, duration_ms INT, parent_node_id VARCHAR(80),
                      subtask_id VARCHAR(128), sequence INT, started_offset_ms INT,
                      exclusive_duration_ms INT, capability VARCHAR(80), model_id VARCHAR(128),
                      failure_class VARCHAR(80), input_tokens INT, output_tokens INT,
                      cache_reused TINYINT DEFAULT 0, retry_count INT DEFAULT 0,
                      created_at DATETIME NOT NULL,
                      INDEX idx_trace_node_trace_id (trace_id),
                      INDEX idx_trace_node_subtask (trace_id,subtask_id),
                      INDEX idx_trace_node_model (model_id),
                      INDEX idx_trace_node_failure_class (failure_class)
                    )
                    """);
        } catch (RuntimeException ignored) {
            // 运行库暂不可用不能阻止影子服务启动；业务执行仍按原健康策略降级。
        }
    }
}

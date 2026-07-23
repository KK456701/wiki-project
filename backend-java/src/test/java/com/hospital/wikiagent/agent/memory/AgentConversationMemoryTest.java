package com.hospital.wikiagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

class AgentConversationMemoryTest {
    @Test
    void migratesLegacySqliteConversationTableWithoutScanningWholeDatabase() throws Exception {
        var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        var source = new SingleConnectionDataSource(connection, true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE med_agent_java_message (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      session_key VARCHAR(512) NOT NULL,
                      hospital_id VARCHAR(128) NOT NULL,
                      user_id VARCHAR(128) NOT NULL,
                      role VARCHAR(16) NOT NULL,
                      content TEXT NOT NULL,
                      rule_id VARCHAR(128),
                      rule_name VARCHAR(255),
                      stat_start VARCHAR(40),
                      stat_end VARCHAR(40),
                      run_id VARCHAR(80),
                      upload_file_key VARCHAR(255),
                      created_at VARCHAR(40) NOT NULL
                    )
                    """);
            AgentConversationMemory memory = new AgentConversationMemory(
                    jdbc, new ObjectMapper());

            memory.initialize();

            var columns = jdbc.queryForList(
                    "PRAGMA table_info(med_agent_java_message)").stream()
                    .map(row -> String.valueOf(row.get("name")))
                    .toList();
            assertThat(columns).contains("caliber_profile_id", "caliber_label");
        } finally {
            source.destroy();
        }
    }

    @Test
    void persistsEightTurnContextWithTenantScopedSessionKey() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("memory_" + System.nanoTime())
                .build();
        AgentConversationMemory memory = new AgentConversationMemory(
                new JdbcTemplate(source), new ObjectMapper());
        memory.initialize();
        HospitalPrincipal principal = principal("hospital_001", "user_001");
        var first = memory.open(principal, "session_001");
        memory.appendUser(first, principal, "计算这个指标从一月到现在", "hospital_001_file.xlsx");
        AgentRunState state = new AgentRunState();
        state.currentRuleId("MQSI2025_001");
        state.currentCaliber(
                "hospital_001_ward_entry_anchor",
                "首次入区时间统计及48小时口径");
        state.currentUploadFileKey("hospital_001_file.xlsx");
        state.lastRunId("RUN_001");
        state.statPeriod("2026-01-01 00:00:00", "2026-07-22 00:00:00");
        state.lastToolResults().add(ToolResult.success(
                "TRIAL_RUN_COMPLETED", "完成", Map.of(
                        "rule_id", "MQSI2025_001",
                        "rule_name", "患者入院48小时内转科的比例",
                        "run_id", "RUN_001")));
        memory.appendAssistant(first, principal, "结果为2.83%", state);

        var restored = memory.open(principal, "session_001");
        var otherHospital = memory.open(
                principal("hospital_002", "user_001"), "session_001");

        assertThat(restored.ruleId()).isEqualTo("MQSI2025_001");
        assertThat(restored.ruleName()).contains("48小时");
        assertThat(restored.caliberProfileId())
                .isEqualTo("hospital_001_ward_entry_anchor");
        assertThat(restored.caliberLabel()).contains("首次入区");
        assertThat(restored.statStart()).isEqualTo("2026-01-01 00:00:00");
        assertThat(restored.statEnd()).isEqualTo("2026-07-22 00:00:00");
        assertThat(restored.lastRunId()).isEqualTo("RUN_001");
        assertThat(restored.uploadFileKey()).isEqualTo("hospital_001_file.xlsx");
        assertThat(restored.recentHistory()).contains("用户：计算这个指标", "助手：结果为2.83%");
        assertThat(restored.structuredSummary()).contains(
                "MQSI2025_001", "RUN_001", "hospital_001_ward_entry_anchor");
        assertThat(otherHospital.recentHistory()).isEmpty();
        assertThat(otherHospital.ruleId()).isNull();
    }

    @Test
    void readsProcessFallbackWhenDatabaseWriteFailsButDatabaseReadReturnsEmpty() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("memory_fallback_" + System.nanoTime())
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        AgentConversationMemory memory = new AgentConversationMemory(jdbc, new ObjectMapper());
        memory.initialize();
        // 模拟 SQLite 短暂写失败：查询仍能成功返回空列表，写入因约束被拒绝。
        // 旧实现会直接采用数据库空列表，从而错误地忽略已经写入的进程内兜底消息。
        jdbc.execute("ALTER TABLE med_agent_java_message ADD CONSTRAINT reject_messages "
                + "CHECK (role = 'blocked')");
        HospitalPrincipal principal = principal("hospital_001", "user_001");
        var conversation = memory.open(principal, "session_fallback");
        memory.appendUser(conversation, principal, "从一月到现在", null);
        AgentRunState state = new AgentRunState();
        state.currentRuleId("MQSI2025_001");
        state.statPeriod("2026-01-01 00:00:00", "2026-07-22 00:00:00");
        memory.appendAssistant(conversation, principal, "结果为2.81%", state);

        var restored = memory.open(principal, "session_fallback");

        assertThat(restored.recentHistory()).contains("用户：从一月到现在", "助手：结果为2.81%");
        assertThat(restored.ruleId()).isEqualTo("MQSI2025_001");
        assertThat(restored.statStart()).isEqualTo("2026-01-01 00:00:00");
        assertThat(restored.statEnd()).isEqualTo("2026-07-22 00:00:00");
    }

    private static HospitalPrincipal principal(String hospitalId, String userId) {
        return new HospitalPrincipal(
                userId, "doctor", hospitalId, Set.of(), false, "AUTH_1");
    }
}

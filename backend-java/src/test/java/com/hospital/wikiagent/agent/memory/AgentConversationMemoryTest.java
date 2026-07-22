package com.hospital.wikiagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

class AgentConversationMemoryTest {
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
        state.currentUploadFileKey("hospital_001_file.xlsx");
        state.lastRunId("RUN_001");
        state.lastToolResults().add(ToolResult.success(
                "TRIAL_RUN_COMPLETED", "完成", Map.of(
                        "rule_id", "MQSI2025_001",
                        "rule_name", "患者入院48小时内转科的比例",
                        "stat_start", "2026-01-01 00:00:00",
                        "stat_end", "2026-07-22 00:00:00",
                        "run_id", "RUN_001")));
        memory.appendAssistant(first, principal, "结果为2.83%", state);

        var restored = memory.open(principal, "session_001");
        var otherHospital = memory.open(
                principal("hospital_002", "user_001"), "session_001");

        assertThat(restored.ruleId()).isEqualTo("MQSI2025_001");
        assertThat(restored.ruleName()).contains("48小时");
        assertThat(restored.statStart()).isEqualTo("2026-01-01 00:00:00");
        assertThat(restored.statEnd()).isEqualTo("2026-07-22 00:00:00");
        assertThat(restored.lastRunId()).isEqualTo("RUN_001");
        assertThat(restored.uploadFileKey()).isEqualTo("hospital_001_file.xlsx");
        assertThat(restored.recentHistory()).contains("用户：计算这个指标", "助手：结果为2.83%");
        assertThat(restored.structuredSummary()).contains("MQSI2025_001", "RUN_001");
        assertThat(otherHospital.recentHistory()).isEmpty();
        assertThat(otherHospital.ruleId()).isNull();
    }

    private static HospitalPrincipal principal(String hospitalId, String userId) {
        return new HospitalPrincipal(
                userId, "doctor", hospitalId, Set.of(), false, "AUTH_1");
    }
}

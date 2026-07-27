package com.hospital.wikiagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.hospital.wikiagent.agent.evidence.EvidenceEnvelope;
import com.hospital.wikiagent.agent.evidence.EvidenceStore;
import com.hospital.wikiagent.agent.evidence.EvidenceVerification;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

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
        assertThat(restored.recentHistory()).contains(
                "用户：计算这个指标", "助手：[摘要] 结果为2.83%");
        assertThat(restored.structuredSummary()).contains(
                "MQSI2025_001", "RUN_001", "hospital_001_ward_entry_anchor");
        assertThat(otherHospital.recentHistory()).isEmpty();
        assertThat(otherHospital.ruleId()).isNull();
    }

    @Test
    void persistsStructuredBatchScopeAcrossMemoryInstances() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("memory_scope_" + System.nanoTime())
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper mapper = new ObjectMapper();
        AgentConversationMemory firstMemory =
                new AgentConversationMemory(jdbc, mapper);
        firstMemory.initialize();
        HospitalPrincipal principal = principal("hospital_001", "user_001");
        var conversation = firstMemory.open(principal, "session_scope");
        firstMemory.rememberQueryScope(conversation, new AgentConversationMemory.QueryScopeState(
                "indicator_trial_run",
                "SUBSET",
                List.of(
                        new AgentConversationMemory.QueryTarget("R1", "指标一"),
                        new AgentConversationMemory.QueryTarget("R2", "指标二")),
                "2026-06-01 00:00:00",
                "2026-07-01 00:00:00"));

        AgentConversationMemory restarted =
                new AgentConversationMemory(jdbc, mapper);
        restarted.initialize();
        var restored = restarted.open(principal, "session_scope");

        assertThat(restored.queryScope()).isNotNull();
        assertThat(restored.queryScope().targetMode()).isEqualTo("SUBSET");
        assertThat(restored.queryScope().targets())
                .extracting("ruleId").containsExactly("R1", "R2");
        assertThat(restored.queryScope().statStart())
                .isEqualTo("2026-06-01 00:00:00");
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

        assertThat(restored.recentHistory()).contains(
                "用户：从一月到现在", "助手：[摘要] 结果为2.81%");
        assertThat(restored.ruleId()).isEqualTo("MQSI2025_001");
        assertThat(restored.statStart()).isEqualTo("2026-01-01 00:00:00");
        assertThat(restored.statEnd()).isEqualTo("2026-07-22 00:00:00");
    }

    private static HospitalPrincipal principal(String hospitalId, String userId) {
        return new HospitalPrincipal(
                userId, "doctor", hospitalId, Set.of(), false, "AUTH_1");
    }

    @Test
    void generatesDigestFromIntentAndRuleName() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("memory_digest_" + System.nanoTime())
                .build();
        AgentConversationMemory memory = new AgentConversationMemory(
                new JdbcTemplate(source), new ObjectMapper());
        memory.initialize();
        HospitalPrincipal principal = principal("hospital_001", "user_001");
        var conversation = memory.open(principal, "session_digest");
        memory.appendUser(conversation, principal, "计算急会诊及时到位率", null);
        AgentRunState state = new AgentRunState();
        state.currentRuleId("MQSI2025_005");
        state.lastIntent("indicator_trial_run");
        state.lastRuleName("急会诊及时到位率");
        state.statPeriod("2026-01-01", "2026-07-01");
        memory.appendAssistant(conversation, principal, "很长的回答内容...", state);

        // 再追加一轮，让第一轮进入摘要区
        memory.appendUser(conversation, principal, "那按科室看呢", null);
        AgentRunState state2 = new AgentRunState();
        state2.lastIntent("indicator_diagnosis");
        state2.lastRuleName("急会诊及时到位率");
        memory.appendAssistant(conversation, principal, "科室诊断结果...", state2);
        memory.appendUser(conversation, principal, "好的谢谢", null);
        AgentRunState state3 = new AgentRunState();
        state3.lastIntent("general_chat");
        memory.appendAssistant(conversation, principal, "不客气", state3);
        memory.appendUser(conversation, principal, "再问一下", null);
        AgentRunState state4 = new AgentRunState();
        memory.appendAssistant(conversation, principal, "回答四", state4);

        var restored = memory.open(principal, "session_digest");
        // 第一轮助手回答应被压缩为 digest 摘要
        assertThat(restored.recentHistory()).contains("[摘要] 已计算急会诊及时到位率的结果（2026-01-01 至 2026-07-01）");
        // 最近助手轮次也只进入摘要，不回灌完整回答
        assertThat(restored.recentHistory()).contains("助手：[摘要] 回答四");
    }

    @Test
    void smartTruncationKeepsRecentCompleteAndOlderAsDigest() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("memory_trunc_" + System.nanoTime())
                .build();
        AgentConversationMemory memory = new AgentConversationMemory(
                new JdbcTemplate(source), new ObjectMapper());
        memory.initialize();
        HospitalPrincipal principal = principal("hospital_001", "user_001");
        var conversation = memory.open(principal, "session_trunc");
        // 写入 8 轮对话
        for (int i = 1; i <= 8; i++) {
            memory.appendUser(conversation, principal, "问题" + i + "：这是一个比较长的用户输入内容", null);
            AgentRunState state = new AgentRunState();
            state.lastIntent("indicator_sql_prepare");
            state.lastRuleName("指标" + i);
            memory.appendAssistant(conversation, principal,
                    "回答" + i + "：" + "A".repeat(500), state);
        }
        var restored = memory.open(principal, "session_trunc");
        String history = restored.recentHistory();
        // 最近用户表达保持完整，助手始终只保留结构化摘要
        assertThat(history).contains("用户：问题8：");
        assertThat(history).contains("[摘要] 已为指标8生成并校验 SQL");
        assertThat(history).doesNotContain("A".repeat(100));
        // 更早轮次应用 digest
        assertThat(history).contains("[摘要] 已为指标1生成并校验 SQL");
        assertThat(history).contains("[摘要] 已为指标2生成并校验 SQL");
        // 总量不超限
        assertThat(history.length()).isLessThanOrEqualTo(12_000);
    }

    @Test
    void evidenceContextInjectedWhenStoreAvailable() {
        DataSource source = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("memory_evidence_" + System.nanoTime())
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        // 创建一个返回固定 Evidence 的 Store
        EvidenceStore stubStore = new EvidenceStore() {
            @Override public void saveEvidence(EvidenceEnvelope value) { }
            @Override public void saveVerification(EvidenceVerification value) { }
            @Override public Optional<EvidenceEnvelope> loadEvidence(String id) { return Optional.empty(); }
            @Override public Optional<EvidenceVerification> loadVerified(String id) { return Optional.empty(); }
            @Override public List<EvidenceEnvelope> recentByRule(String hospitalId, String ruleId, int limit) {
                if ("MQSI2025_005".equals(ruleId)) {
                    return List.of(new EvidenceEnvelope(
                            null, "EVD_001", "TRACE_1", "SUB_1", "trial_run",
                            hospitalId, ruleId, "2025", "2026-01-01", "2026-07-01",
                            "trial_run_indicator_sql", "RUN_001", "fp1", "fp2",
                            "internal", Instant.now(), null, null,
                            Map.of("numerator_count", 35, "denominator_count", 40,
                                    "result_value", "87.5%")));
                }
                return List.of();
            }
        };
        AgentConversationMemory memory = new AgentConversationMemory(
                jdbc, new ObjectMapper(), stubStore);
        memory.initialize();
        HospitalPrincipal principal = principal("hospital_001", "user_001");
        // 先写入一条带 ruleId 的消息，让 open() 能恢复 ruleId
        var first = memory.open(principal, "session_ev");
        memory.appendUser(first, principal, "计算急会诊", null);
        AgentRunState state = new AgentRunState();
        state.currentRuleId("MQSI2025_005");
        memory.appendAssistant(first, principal, "结果", state);

        var restored = memory.open(principal, "session_ev");
        assertThat(restored.evidenceContext()).contains("[EV] trial_run:");
        assertThat(restored.evidenceContext()).contains("分子=35");
        assertThat(restored.evidenceContext()).contains("结果=87.5%");
    }
}

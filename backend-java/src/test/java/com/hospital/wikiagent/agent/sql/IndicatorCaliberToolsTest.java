package com.hospital.wikiagent.agent.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.AgentRuntimeContext;
import com.hospital.wikiagent.agent.tools.PolicyDecision;
import com.hospital.wikiagent.agent.tools.PolicyDecision.Decision;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.RuleReadRepository;

class IndicatorCaliberToolsTest {
    private RuleReadRepository rules;
    private IndicatorSqlTools sqlTools;
    private IndicatorCaliberTools tools;
    private AgentRunState state;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        rules = mock(RuleReadRepository.class);
        sqlTools = mock(IndicatorSqlTools.class);
        tools = new IndicatorCaliberTools(rules, sqlTools);
        state = new AgentRunState();
        state.currentRuleId("MQSI2025_001");
        var runtime = new AgentRuntimeContext(
                new HospitalPrincipal(
                        "user_001", "doctor", "hospital_001",
                        Set.of(), false, "session_001"),
                "request_001", "trace_001", "business");
        context = new ToolExecutionContext(
                runtime, "subtask_001", state,
                new PolicyDecision(Decision.ALLOW, "POLICY_ALLOW", "", "test"));
        when(rules.caliberProfiles("MQSI2025_001", "hospital_001"))
                .thenReturn(List.of(profile()));
        when(rules.effectiveRule("MQSI2025_001", "hospital_001"))
                .thenReturn(Map.of(
                        "rule_id", "MQSI2025_001",
                        "rule_name", "患者入院48小时内转科的比例",
                        "hospital_version", 4));
    }

    @Test
    void resolvesApprovedProfileAndNeverAcceptsPhysicalFieldFromUser() {
        ToolResult result = tools.resolve(
                new IndicatorCaliberTools.ResolveInput(
                        "MQSI2025_001", "那根据入区怎么算", null,
                        "2026-01-01T00:00:00", "2026-07-23T00:00:00"),
                context);

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("CALIBER_PROFILE_RESOLVED");
        assertThat(result.data())
                .containsEntry(
                        "caliber_profile_id",
                        "hospital_001_ward_entry_anchor")
                .containsEntry("period_anchor_label", "首次入区时间")
                .containsEntry("current_rule_version", "4");
        assertThat(state.currentCaliberProfileId())
                .isEqualTo("hospital_001_ward_entry_anchor");
    }

    @Test
    void preservesProfileAndSqlChainAcrossPreparationAndTrial() {
        String profileId = "hospital_001_ward_entry_anchor";
        state.currentCaliber(profileId, "首次入区时间统计及48小时口径");
        when(sqlTools.prepareDiagnostic(
                any(IndicatorSqlTools.PrepareInput.class),
                eq(profileId),
                eq(Map.of("transfer_minutes_threshold", 2880)),
                eq(Map.of(
                        "period_time", "ward_entry_time",
                        "admit_time", "ward_entry_time")),
                eq(context))).thenReturn(ToolResult.success(
                        "SQL_OBJECT_PREPARED",
                        "prepared",
                        Map.of(
                                "sql_id", "SQL_001",
                                "rule_id", "MQSI2025_001",
                                "stat_start", "2026-01-01 00:00:00",
                                "stat_end", "2026-07-23 00:00:00")));

        ToolResult prepared = tools.prepare(
                new IndicatorCaliberTools.PrepareInput(
                        "MQSI2025_001", profileId,
                        "2026-01-01T00:00:00", "2026-07-23T00:00:00"),
                context);

        assertThat(prepared.code()).isEqualTo("CALIBER_SQL_PREPARED");
        when(sqlTools.trial(new IndicatorSqlTools.TrialInput("SQL_001"), context))
                .thenReturn(ToolResult.success(
                        "TRIAL_RUN_COMPLETED",
                        "trial",
                        Map.of(
                                "sql_id", "SQL_001",
                                "run_id", "RUN_001",
                                "rule_id", "MQSI2025_001",
                                "stat_start", "2026-01-01T00:00:00",
                                "stat_end", "2026-07-23T00:00:00",
                                "numerator_count", 8,
                                "denominator_count", 120,
                                "result_value", 6.67)));

        ToolResult trial = tools.trial(
                new IndicatorCaliberTools.TrialInput("SQL_001", profileId),
                context);

        assertThat(trial.ok()).isTrue();
        assertThat(trial.code()).isEqualTo("CALIBER_TRIAL_RUN_COMPLETED");
        assertThat(trial.data())
                .containsEntry("caliber_sql_id", "SQL_001")
                .containsEntry("caliber_profile_id", profileId)
                .containsEntry("numerator_count", 8);
    }

    @Test
    void rejectsProfileOutsideApprovedAllowList() {
        ToolResult result = tools.resolve(
                new IndicatorCaliberTools.ResolveInput(
                        "MQSI2025_001", "", "user_supplied_profile",
                        "2026-01-01T00:00:00", "2026-07-23T00:00:00"),
                context);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("CALIBER_PROFILE_NOT_FOUND");
    }

    private static Map<String, Object> profile() {
        return Map.ofEntries(
                Map.entry("profile_id", "hospital_001_ward_entry_anchor"),
                Map.entry("label", "首次入区时间统计及48小时口径"),
                Map.entry("aliases", List.of("入区", "首次入区")),
                Map.entry("source_level", "hospital_history"),
                Map.entry("source_version", "2026-07"),
                Map.entry("status", "approved"),
                Map.entry("effective_from", "2026-01-01"),
                Map.entry("caliber_definition", "按首次入区时间统计。"),
                Map.entry("numerator_rule", "首次入区后48小时内非ICU转科人次数。"),
                Map.entry("denominator_rule", "同期首次入区患者人次数。"),
                Map.entry("period_anchor_label", "首次入区时间"),
                Map.entry("elapsed_anchor_label", "首次入区时间"),
                Map.entry("field_role_overrides", Map.of(
                        "period_time", "ward_entry_time",
                        "admit_time", "ward_entry_time")),
                Map.entry("parameter_overrides", Map.of(
                        "transfer_minutes_threshold", 2880)),
                Map.entry("difference_dimensions", List.of("统计时间锚点")));
    }
}

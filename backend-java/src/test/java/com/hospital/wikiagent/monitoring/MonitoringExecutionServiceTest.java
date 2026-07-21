package com.hospital.wikiagent.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolGateway;
import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.auth.HospitalPrincipal;

class MonitoringExecutionServiceTest {
    @Test
    void manualRunUsesControlledSqlChainAndPersistsWaveResult() {
        MonitoringRepository repository = mock(MonitoringRepository.class);
        ToolGateway gateway = mock(ToolGateway.class);
        AgentTraceService traces = mock(AgentTraceService.class);
        Map<String, Object> plan = plan();
        when(repository.plan("PLAN_1", "hospital_001")).thenReturn(Optional.of(plan));
        when(repository.successfulResult(anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(repository.createRunResult(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> saved = new LinkedHashMap<>(invocation.getArgument(0));
            saved.put("id", 7L);
            return saved;
        });
        when(repository.updateWave(any(Long.class), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("id", 7L); saved.put("result_value", 2.83);
            saved.put("wave_status", invocation.<Map<String, Object>>getArgument(1).get("wave_status"));
            saved.put("is_abnormal", false); saved.put("stat_period", "2026-01-01 00:00:00~2026-04-01 00:00:00");
            return saved;
        });
        when(gateway.execute(anyString(), anyMap(), any(), any())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            AgentRunState state = invocation.getArgument(3);
            ToolResult result = switch (tool) {
                case "get_effective_rule" -> ToolResult.success("EFFECTIVE_RULE_FOUND", "ok", Map.of(
                        "effective_level", "hospital", "national_version", "2025", "hospital_version", 4));
                case "prepare_indicator_sql" -> ToolResult.success("SQL_PREPARED", "ok", Map.of("sql_id", "SQL_1"));
                case "trial_run_indicator_sql" -> {
                    assertThat(state.validatedSqlIds()).containsExactly("SQL_1");
                    yield ToolResult.success("TRIAL_RUN_COMPLETED", "ok", Map.of(
                            "sql_id", "SQL_1", "run_id", "RUN_1", "result_value", 2.83,
                            "numerator_count", 11, "denominator_count", 389, "no_sample", false,
                            "duration_ms", 25, "db_source_id", "win60_qa_991827"));
                }
                default -> throw new AssertionError("unexpected tool " + tool);
            };
            return CompletableFuture.completedFuture(result);
        });
        MonitoringExecutionService service = new MonitoringExecutionService(repository,
                new MonitoringPeriodResolver(), new MonitoringWaveDetector(), gateway, traces, 600);

        Map<String, Object> result = service.runManual("PLAN_1", "hospital_001",
                "2026-01-01~2026-03-31", principal());

        assertThat(result.get("result_value")).isEqualTo(2.83);
        assertThat(result.get("wave_status")).isEqualTo("baseline_insufficient");
        ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
        verify(repository).createRunResult(saved.capture());
        assertThat(saved.getValue()).containsEntry("run_id", "RUN_1")
                .containsEntry("stat_period", "2026-01-01 00:00:00~2026-04-01 00:00:00")
                .containsEntry("run_status", "success");
    }

    private static Map<String, Object> plan() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("plan_id", "PLAN_1"); value.put("hospital_id", "hospital_001");
        value.put("rule_id", "MQSI2025_001"); value.put("frequency", "monthly");
        value.put("run_time", "02:00"); value.put("day_of_month", 1); value.put("timezone", "Asia/Shanghai");
        value.put("mom_enabled", true); value.put("mom_threshold_pct", 20.0);
        value.put("yoy_enabled", true); value.put("yoy_threshold_pct", 30.0);
        return value;
    }

    private static HospitalPrincipal principal() {
        return new HospitalPrincipal("user_001", "doctor", "hospital_001", Set.of(), false, "SESSION_1");
    }
}

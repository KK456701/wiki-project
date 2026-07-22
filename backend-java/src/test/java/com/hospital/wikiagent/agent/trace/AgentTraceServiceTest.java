package com.hospital.wikiagent.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import com.hospital.wikiagent.agent.runtime.AgentRunObserver;
import com.hospital.wikiagent.agent.runtime.AgentRunResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

class AgentTraceServiceTest {
    @Test
    void storesSafeNodesAndRejectsCrossHospitalReads() {
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("trace_" + System.nanoTime() + ";MODE=MySQL")
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        new AgentTraceSchemaInitializer(jdbc).initialize();
        AgentTraceService service = new AgentTraceService(
                new AgentTraceRepository(jdbc), new ObjectMapper());
        HospitalPrincipal hospital = principal("hospital_001");
        service.start("TRACE_001", "SESSION_001", hospital, "急会诊怎么算");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "trace_node");
        event.put("node_name", "tool_result");
        event.put("node_type", "tool");
        event.put("status", "success");
        event.put("duration_ms", 12);
        event.put("tool_name", "prepare_indicator_sql");
        event.put("input", Map.of("rule_id", "MQSI2025_005", "sql", "SELECT secret"));
        event.put("output", Map.of(
                "token", "secret-token", "sql_id", "SQL_001",
                "sql_preview", "SELECT private_table"));
        AtomicInteger forwarded = new AtomicInteger();
        AgentRunObserver observer = service.observer("TRACE_001", ignored -> forwarded.incrementAndGet());
        observer.onEvent(event);
        assertThat(forwarded.get()).isZero();
        service.finish("TRACE_001", new AgentRunResult(
                "已完成", "final_answer", "TRACE_001", "SESSION_001", 1, null, null));

        Map<String, Object> trace = service.get("TRACE_001", hospital);
        assertThat(trace.get("final_status")).isEqualTo("success");
        @SuppressWarnings("unchecked")
        Map<String, Object> node = ((java.util.List<Map<String, Object>>) trace.get("nodes")).get(0);
        assertThat(node.get("node_title")).isEqualTo("执行并观察工具结果");
        assertThat(String.valueOf(node.get("input_data"))).contains("[已脱敏]")
                .doesNotContain("SELECT secret");
        assertThat(String.valueOf(node.get("output_data"))).doesNotContain("secret-token");
        assertThat(String.valueOf(node.get("output_data"))).doesNotContain("private_table");
        assertThat(String.valueOf(node.get("output_data"))).contains("SQL_001");
        assertThatThrownBy(() -> service.get("TRACE_001", principal("hospital_002")))
                .isInstanceOf(AgentTraceService.AgentTraceNotFoundException.class);

        AgentTraceService.RunFilters filters = new AgentTraceService.RunFilters(
                null, null, null, null, null, null, 100);
        assertThat(service.list(hospital, filters)).containsEntry("count", 1);
        Map<String, Object> metrics = service.metrics(hospital, filters);
        assertThat(metrics).containsEntry("request_count", 1)
                .containsEntry("success_rate", 1.0);
        assertThat(String.valueOf(metrics.get("tools"))).contains("prepare_indicator_sql");
        assertThat(service.list(principal("hospital_002"), filters)).containsEntry("count", 0);
        assertThat(new AgentTraceRepository(jdbc).prune(java.time.LocalDateTime.now().plusDays(1)))
                .isEqualTo(1);
    }

    private static HospitalPrincipal principal(String hospitalId) {
        return new HospitalPrincipal(
                "user_001", "doctor", hospitalId, Set.of(), false, "AUTH_1");
    }
}

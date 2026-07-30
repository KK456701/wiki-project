package com.hospital.wikiagent.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
        event.put("nodeName", "tool_result");
        event.put("nodeType", "tool");
        event.put("status", "success");
        event.put("durationMs", 12);
        event.put("toolName", "prepare_indicator_sql");
        String longHistory = "历史上下文".repeat(900) + "__完整输入末尾__";
        event.put("input", Map.of(
                "ruleId", "MQSI2025_005",
                "history", longHistory,
                "sql", "SELECT secret"));
        event.put("output", Map.of(
                "token", "secret-token", "sqlId", "SQL_001",
                "sqlPreview", "SELECT private_table"));
        AtomicReference<Map<String, Object>> forwarded = new AtomicReference<>();
        AgentRunObserver observer = service.observer("TRACE_001", forwarded::set);
        observer.onEvent(event);
        assertThat(forwarded.get())
                .containsEntry("event", "stage_update")
                .containsEntry("message", "执行并观察工具结果")
                .containsEntry("toolName", "prepare_indicator_sql")
                .doesNotContainKeys("input", "output");
        assertThat(String.valueOf(forwarded.get()))
                .doesNotContain("SELECT secret")
                .doesNotContain("secret-token");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("event", "trace_node");
        second.put("nodeName", "final_answer_llm");
        second.put("nodeType", "llm");
        second.put("status", "success");
        second.put("durationMs", 8);
        second.put("input", Map.of("messages", java.util.List.of()));
        second.put("output", Map.of("answerLength", 12));
        observer.onEvent(second);
        service.finish("TRACE_001", new AgentRunResult(
                "已完成", "final_answer", "TRACE_001", "SESSION_001", 1, null, null));

        Map<String, Object> trace = service.get("TRACE_001", hospital);
        assertThat(trace.get("finalStatus")).isEqualTo("success");
        @SuppressWarnings("unchecked")
        Map<String, Object> node = ((java.util.List<Map<String, Object>>) trace.get("nodes")).get(0);
        assertThat(node.get("nodeTitle")).isEqualTo("执行并观察工具结果");
        assertThat(node)
                .containsEntry("flowStage", "execution")
                .containsEntry("flowStageTitle", "工具与数据库执行")
                .containsEntry("flowStageOrder", 4);
        assertThat(String.valueOf(node.get("inputData"))).contains("[已脱敏]")
                .contains("__完整输入末尾__")
                .doesNotContain("SELECT secret");
        assertThat(String.valueOf(node.get("outputData"))).doesNotContain("secret-token");
        assertThat(String.valueOf(node.get("outputData"))).doesNotContain("private_table");
        assertThat(String.valueOf(node.get("outputData"))).contains("SQL_001");
        assertThat(String.valueOf(trace.get("flowEdges")))
                .contains("fromNodeId", "toNodeId", "sequence");
        @SuppressWarnings("unchecked")
        Map<String, Object> answerNode =
                ((java.util.List<Map<String, Object>>) trace.get("nodes")).get(1);
        assertThat(answerNode)
                .containsEntry("flowStage", "answer")
                .containsEntry("flowStageOrder", 6);
        assertThatThrownBy(() -> service.get("TRACE_001", principal("hospital_002")))
                .isInstanceOf(AgentTraceService.AgentTraceNotFoundException.class);

        AgentTraceService.RunFilters filters = new AgentTraceService.RunFilters(
                null, null, null, null, null, null, 100);
        assertThat(service.list(hospital, filters)).containsEntry("count", 1);
        Map<String, Object> metrics = service.metrics(hospital, filters);
        assertThat(metrics).containsEntry("requestCount", 1)
                .containsEntry("successRate", 1.0);
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

package com.hospital.wikiagent.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
class AgentContractJacksonTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void readsAndWritesFrozenSnakeCaseFields() throws Exception {
        AgentChatRequest request = objectMapper.readValue("""
                {
                  "query": "查询指标",
                  "session_id": "session-1",
                  "model_id": "deepseek-v4-pro",
                  "file_key": "hospital_001_无标题.xlsx"
                }
                """, AgentChatRequest.class);
        String response = objectMapper.writeValueAsString(
                new AgentChatResponse("已完成", "final_answer", "TRACE_1", "session-1", 2));
        String upload = objectMapper.writeValueAsString(
                new UploadResponse("hospital_001_file.xlsx", "file.xlsx", 128));

        assertThat(request.sessionId()).isEqualTo("session-1");
        assertThat(response).contains("\"stop_reason\"", "\"trace_id\"", "\"step_count\"");
        assertThat(upload).contains("\"file_key\"", "\"file_name\"", "\"size_bytes\"");
    }

    @Test
    void rejectsUnknownIdentityField() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"query\":\"查询指标\",\"hospital_id\":\"other\"}",
                AgentChatRequest.class))
                .hasMessageContaining("hospital_id");
    }

    @Test
    void planIrUsesFrozenSnakeCaseAndBusinessEnumValues() throws Exception {
        RequestPlan plan = new RequestPlan(
                null,
                PlanIntent.INDICATOR_TRIAL_RUN,
                "计算急会诊及时到位率",
                new RequestPlan.TargetIndicator("急会诊及时到位率", "MQSI2025_005"),
                new RequestPlan.TimeExpression("1月至3月", "2026-01-01", "2026-04-01"),
                List.of(RequestedOutput.TRIAL_RESULT),
                List.of(),
                List.of());

        String payload = objectMapper.writeValueAsString(plan);
        RequestPlan restored = objectMapper.readValue(payload, RequestPlan.class);

        assertThat(payload).contains(
                "\"schema_version\":\"request-plan-v2\"",
                "\"indicator_trial_run\"",
                "\"target_indicator\"",
                "\"trial_result\"");
        assertThat(restored.intent()).isEqualTo(PlanIntent.INDICATOR_TRIAL_RUN);
        assertThat(restored.targetIndicator().ruleId()).isEqualTo("MQSI2025_005");
    }
}

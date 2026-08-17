package com.hospital.wikiagent.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import java.util.List;

import com.hospital.wikiagent.agent.ir.ExplanationFocus;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

import com.fasterxml.jackson.databind.json.JsonMapper;

class AgentContractJacksonTest {
    private final JsonMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void readsAndWritesCamelCaseFields() throws Exception {
        AgentChatRequest request = objectMapper.readValue("""
                {
                  "query": "查询指标",
                  "sessionId": "session-1",
                  "modelId": "deepseek-v4-pro",
                  "fileKey": "hospital_001_无标题.xlsx"
                }
                """, AgentChatRequest.class);
        String response = objectMapper.writeValueAsString(
                new AgentChatResponse("已完成", "final_answer", "TRACE_1", "session-1", 2));
        String upload = objectMapper.writeValueAsString(
                new UploadResponse("hospital_001_file.xlsx", "file.xlsx", 128));
        String clarification = objectMapper.writeValueAsString(new AgentClarification(
                "TIME_RANGE_AMBIGUOUS",
                "time_range",
                "请选择统计时间",
                "你希望统计哪个时间范围？",
                "选择后将继续原任务。",
                "single",
                List.of(new AgentClarification.Option(
                        "time:ytd",
                        "今年至今",
                        "2026-01-01 至 2026-07-23",
                        "从今年第一天统计到今天",
                        "常用时间")),
                true,
                "例如：2026-01-01 至 2026-03-31",
                "继续原问题："));

        assertThat(request.sessionId()).isEqualTo("session-1");
        assertThat(response).contains("\"stopReason\"", "\"traceId\"", "\"stepCount\"");
        assertThat(upload).contains("\"fileKey\"", "\"fileName\"", "\"sizeBytes\"");
        assertThat(clarification).contains(
                "\"helpText\"",
                "\"selectionMode\":\"single\"",
                "\"allowFreeText\":true",
                "\"resumePrefix\"",
                "\"clarificationId\":\"CLR_",
                "\"field\":\"time\"",
                "\"resumeToken\"");
    }

    @Test
    void rejectsUnknownIdentityField() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"query\":\"查询指标\",\"hospitalId\":\"other\"}",
                AgentChatRequest.class))
                .hasMessageContaining("hospitalId");
    }

    @Test
    void planIrUsesCamelCaseKeysAndBusinessEnumValues() throws Exception {
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
                "\"schemaVersion\":\"request-plan-v3\"",
                "\"indicator_trial_run\"",
                "\"targetIndicator\"",
                "\"explanationFocuses\":[\"overview\"]",
                "\"trial_result\"");
        assertThat(restored.intent()).isEqualTo(PlanIntent.INDICATOR_TRIAL_RUN);
        assertThat(restored.targetIndicator().ruleId()).isEqualTo("MQSI2025_005");
    }

    @Test
    void defaultsMissingExplanationFocusAndReadsLegacyV2Plan() throws Exception {
        RequestPlan restored = objectMapper.readValue("""
                {
                  "schemaVersion": "request-plan-v2",
                  "intent": "rule_explanation",
                  "goal": "解释指标分子",
                  "targetIndicator": {"rawName": "急会诊及时到位率"},
                  "targetCaliber": {},
                  "timeExpression": {},
                  "requestedOutputs": ["explanation"],
                  "constraints": [],
                  "semanticAmbiguities": [],
                  "confidence": 1.0
                }
                """, RequestPlan.class);

        assertThat(restored.explanationFocuses())
                .containsExactly(ExplanationFocus.OVERVIEW);
    }
}

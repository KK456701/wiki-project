package com.hospital.wikiagent.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.ObjectMapper;

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

        assertThat(request.sessionId()).isEqualTo("session-1");
        assertThat(response).contains("\"stop_reason\"", "\"trace_id\"", "\"step_count\"");
    }

    @Test
    void rejectsUnknownIdentityField() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"query\":\"查询指标\",\"hospital_id\":\"other\"}",
                AgentChatRequest.class))
                .hasMessageContaining("hospital_id");
    }
}

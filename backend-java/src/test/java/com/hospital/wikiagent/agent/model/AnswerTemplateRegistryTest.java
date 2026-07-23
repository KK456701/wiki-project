package com.hospital.wikiagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestedOutput;

class AnswerTemplateRegistryTest {
    private final AnswerTemplateRegistry registry = new AnswerTemplateRegistry();

    @Test
    void providesAReadableTemplateForEveryPlanIntent() {
        for (PlanIntent intent : PlanIntent.values()) {
            var template = registry.resolve(intent, List.of());
            assertThat(template.id()).isNotBlank();
            assertThat(template.version()).isEqualTo("v1");
            assertThat(template.body()).isNotBlank();
            assertThat(template.body()).doesNotContain("tool_calls", "invoke name=");
        }
        assertThat(registry.all()).hasSize(PlanIntent.values().length);
    }

    @Test
    void letsSpecificRequestedOutputOverrideGenericIntentTemplate() {
        var template = registry.resolve(
                PlanIntent.GENERAL_CHAT,
                List.of(RequestedOutput.DIFFERENCE_DIAGNOSIS_REPORT));

        assertThat(template.id()).isEqualTo("difference-diagnosis-report");
        assertThat(template.kind()).isEqualTo("report");
        assertThat(template.requiredSections())
                .contains("## 双方结果", "## 诊断结论", "## 证据限制");
    }
}

package com.hospital.wikiagent.agent.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * IR 枚举对模型书写差异的容错约束。
 *
 * <p>Planner 输出由大模型生成，snake_case 常被写成驼峰或连字符。归一化必须只消除
 * 书写差异：能认出同一个取值的各种写法，但不能把未知文本映射成某个合法状态，
 * 也不能让两个不同取值在归一化后互相碰撞。</p>
 */
class IrEnumWritingVariantTest {

    @Test
    void camelCaseOutputResolvesToSnakeCaseValue() {
        // 实测 Qwen-Plus 连续两次输出 trialResult，此前会让整份计划判为无效。
        assertThat(RequestedOutput.fromValue("trialResult")).isEqualTo(RequestedOutput.TRIAL_RESULT);
        assertThat(RequestedOutput.fromValue("caliberTrialResult"))
                .isEqualTo(RequestedOutput.CALIBER_TRIAL_RESULT);
        assertThat(PlanIntent.fromValue("indicatorTrialRun"))
                .isEqualTo(PlanIntent.INDICATOR_TRIAL_RUN);
        assertThat(PlanCapability.fromValue("executeCaliberTrialRun"))
                .isEqualTo(PlanCapability.EXECUTE_CALIBER_TRIAL_RUN);
        assertThat(ExplanationFocus.fromValue("timeDimension"))
                .isEqualTo(ExplanationFocus.TIME_DIMENSION);
    }

    @Test
    void otherWritingVariantsResolveToSameValue() {
        assertThat(RequestedOutput.fromValue("trial_result")).isEqualTo(RequestedOutput.TRIAL_RESULT);
        assertThat(RequestedOutput.fromValue("trial-result")).isEqualTo(RequestedOutput.TRIAL_RESULT);
        assertThat(RequestedOutput.fromValue("TRIAL_RESULT")).isEqualTo(RequestedOutput.TRIAL_RESULT);
        assertThat(RequestedOutput.fromValue("Trial Result")).isEqualTo(RequestedOutput.TRIAL_RESULT);
    }

    @Test
    void unknownTextIsStillRejected() {
        // 归一化不得给未知语义兜底：模型编造的输出目标必须报错，不能静默变成成功状态。
        assertThatThrownBy(() -> RequestedOutput.fromValue("patientList"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("patientList");
        assertThatThrownBy(() -> PlanIntent.fromValue(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanCapability.fromValue(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExplanationFocus.fromValue("numerator_and_denominator"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalValuesDoNotCollideWithinEachEnum() {
        // 归一化安全的前提：同一枚举内任意两个取值归一化后都不相同。
        // 新增取值若与既有取值仅差分隔符（如 timedimension / time_dimension），此处会失败。
        assertNoCollision("RequestedOutput", RequestedOutput.values(), RequestedOutput::value);
        assertNoCollision("PlanIntent", PlanIntent.values(), PlanIntent::value);
        assertNoCollision("PlanCapability", PlanCapability.values(), PlanCapability::value);
        assertNoCollision("ExplanationFocus", ExplanationFocus.values(), ExplanationFocus::value);
    }

    private <E extends Enum<E>> void assertNoCollision(
            String enumName, E[] values, Function<E, String> valueOf) {
        Map<String, String> seen = new HashMap<>();
        for (E candidate : values) {
            String canonical = IrEnumCodec.canonical(valueOf.apply(candidate));
            String previous = seen.put(canonical, candidate.name());
            assertThat(previous)
                    .withFailMessage("%s 的 %s 与 %s 归一化后冲突（%s）",
                            enumName, candidate.name(), previous, canonical)
                    .isNull();
        }
    }
}

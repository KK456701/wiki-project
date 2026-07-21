package com.hospital.wikiagent.agent.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.PlanCapability;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.agent.planning.ControllerDecision.ControllerAction;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;

import tools.jackson.databind.ObjectMapper;

class CapabilityPlanningTest {
    private CapabilitySpecRegistry registry;
    private PlanCompiler compiler;

    @BeforeEach
    void setUp() {
        registry = defaultRegistry();
        compiler = new PlanCompiler(registry, new ObjectMapper());
    }

    @Test
    void compilesTrialRunIntoVersionedTopologicalIr() {
        RequestPlan plan = trialPlan();

        CompiledPlanIR ir = compiler.compile(plan);

        assertThat(ir.schemaVersion()).isEqualTo("compiled-plan-ir-v1");
        assertThat(ir.capabilityRegistryVersion()).isEqualTo("capability-registry-v1");
        assertThat(ir.planId()).startsWith("PLAN_").hasSize(21);
        assertThat(ir.nodes()).extracting(CompiledPlanIR.PlanNode::capability).containsExactly(
                PlanCapability.RESOLVE_INDICATOR,
                PlanCapability.RESOLVE_EFFECTIVE_RULE,
                PlanCapability.RESOLVE_TIME_RANGE,
                PlanCapability.PREPARE_VERIFIED_SQL,
                PlanCapability.EXECUTE_TRIAL_RUN,
                PlanCapability.COMPOSE_ANSWER);
    }

    @Test
    void controllerAndDispatchExposeOnlyTheCompiledTool() {
        RequestPlan plan = trialPlan();
        PlanValidation validation = PlanValidation.valid(new PlanValidation.ResolvedTimeRange(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 4, 1, 0, 0),
                "2026年1月至3月"));
        CompiledPlanIR ir = compiler.compile(plan);
        AgentRunState state = new AgentRunState();
        AgentStateController controller = new AgentStateController(registry);

        ControllerDecision first = controller.nextDecision(ir, validation, state);
        PlanningExecution execution = new PlanningExecution(plan, ir, validation, registry);
        DeterministicDispatch.ToolCall call = new DeterministicDispatch()
                .buildToolCall(execution, first, state, "计算结果");

        assertThat(first.action()).isEqualTo(ControllerAction.EXECUTE_TOOL);
        assertThat(first.toolNames()).containsExactly("search_indicator_rules");
        assertThat(call.name()).isEqualTo("search_indicator_rules");
        assertThat(call.arguments()).containsEntry("query", "患者入院48小时内转科的比例");

        state.lastToolResults().add(ToolResult.success(
                "RULE_SEARCHED", "找到 1 个匹配指标。",
                Map.of("resolved_rule_id", "MQSI2025_001", "matches", List.of())));
        ControllerDecision second = controller.nextDecision(ir, validation, state);
        assertThat(second.toolNames()).containsExactly("get_effective_rule");
    }

    @Test
    void registryRejectsDuplicateProducerUnknownToolAndCycle() {
        CapabilitySpec duplicateA = spec(
                PlanCapability.RESOLVE_INDICATOR, Set.of(), Set.of("same"), "tool_a");
        CapabilitySpec duplicateB = spec(
                PlanCapability.RESOLVE_EFFECTIVE_RULE, Set.of(), Set.of("same"), "tool_b");
        assertThatThrownBy(() -> new CapabilitySpecRegistry(
                List.of(duplicateA, duplicateB), Set.of("tool_a", "tool_b")))
                .hasMessageContaining("重复 Fact Producer");

        assertThatThrownBy(() -> new CapabilitySpecRegistry(
                List.of(duplicateA), Set.of()))
                .hasMessageContaining("未知工具");

        CapabilitySpec cycleA = spec(
                PlanCapability.RESOLVE_INDICATOR, Set.of("fact_b"), Set.of("fact_a"), "tool_a");
        CapabilitySpec cycleB = spec(
                PlanCapability.RESOLVE_EFFECTIVE_RULE, Set.of("fact_a"), Set.of("fact_b"), "tool_b");
        assertThatThrownBy(() -> new CapabilitySpecRegistry(
                List.of(cycleA, cycleB), Set.of("tool_a", "tool_b")))
                .hasMessageContaining("依赖环");
    }

    private static RequestPlan trialPlan() {
        return new RequestPlan(
                null,
                PlanIntent.INDICATOR_TRIAL_RUN,
                "计算患者入院48小时内转科比例",
                new RequestPlan.TargetIndicator("患者入院48小时内转科的比例", null),
                new RequestPlan.TimeExpression(
                        "2026年1月至3月", "2026-01-01 00:00:00", "2026-04-01 00:00:00"),
                List.of(RequestedOutput.TRIAL_RESULT),
                List.of(),
                List.of());
    }

    private static CapabilitySpecRegistry defaultRegistry() {
        List<CapabilitySpec> specs = CapabilitySpecRegistry.defaultSpecs();
        Set<String> tools = specs.stream()
                .map(CapabilitySpec::toolName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return new CapabilitySpecRegistry(specs, tools);
    }

    private static CapabilitySpec spec(
            PlanCapability capability, Set<String> requires, Set<String> produces, String tool) {
        String completion = produces.iterator().next();
        CapabilitySpec.ArgumentCompiler compiler = switch (capability) {
            case RESOLVE_INDICATOR -> (execution, state, message) -> Map.of(
                    "query", execution.requestPlan().targetIndicator().rawName(), "limit", 5);
            case RESOLVE_EFFECTIVE_RULE -> (execution, state, message) -> Map.of(
                    "rule_id", "MQSI2025_001");
            default -> (execution, state, message) -> Map.of();
        };
        return new CapabilitySpec(
                capability, "1.0", requires, produces, tool, "agent.tool.execute", compiler,
                "fact_present", (facts, value) -> facts.contains(value.completionFact()),
                "none", "evidence_only", completion);
    }

}

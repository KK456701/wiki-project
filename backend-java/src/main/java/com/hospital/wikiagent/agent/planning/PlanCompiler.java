package com.hospital.wikiagent.agent.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.CompiledPlanIR.PlanNode;
import com.hospital.wikiagent.agent.ir.PlanCapability;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier;
import com.hospital.wikiagent.agent.model.PromptCatalog;
import com.hospital.wikiagent.agent.model.SpringAiModelInvoker;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 将模型给出的业务 RequestPlan 编译成拓扑有序、可校验的 CompiledPlan IR。
 *
 * <p>编译过程不调用模型：它从请求输出事实递归补齐 Capability 依赖，并把工具、策略动作、
 * 参数编译器、Verifier 和重试规则冻结到 IR 节点。相同 RequestPlan 会生成相同 planId，便于
 * Trace 对比并阻止 Replanner 重复已经失败的路径。</p>
 */
@Component
public class PlanCompiler {
    private final CapabilitySpecRegistry registry;
    private final ObjectMapper objectMapper;

    public PlanCompiler(CapabilitySpecRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 将已通过 Schema 校验的业务计划编译为拓扑有序 IR。
     */
    public CompiledPlanIR compile(RequestPlan plan) {
        Set<String> outputFacts = registry.requiredOutputFacts(plan);
        List<PlanCapability> capabilities = registry.compileCapabilities(outputFacts);
        Set<String> requiredFacts = new HashSet<>(outputFacts);
        List<PlanNode> nodes = new ArrayList<>();
        for (PlanCapability capability : capabilities) {
            CapabilitySpec spec = registry.require(capability);
            if (spec.completionFact() != null) {
                requiredFacts.add(spec.completionFact());
            }
            nodes.add(new PlanNode(
                    capability,
                    spec.version(),
                    spec.requires(),
                    spec.produces(),
                    spec.toolName(),
                    spec.policyAction(),
                    spec.verifierName(),
                    spec.retryPolicy(),
                    spec.answerMode()));
        }
        return new CompiledPlanIR(
                planId(plan),
                CompiledPlanIR.VERSION,
                plan.schemaVersion(),
                CapabilitySpecRegistry.VERSION,
                PromptCatalog.VERSION,
                SpringAiModelInvoker.VERSION,
                EvidenceVerifier.VERSION,
                plan.intent(),
                plan.goal(),
                nodes,
                requiredFacts,
                Set.copyOf(plan.requestedOutputs()));
    }

    private String planId(RequestPlan plan) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(plan);
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
            return "PLAN_" + digest.substring(0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成计划编号", exception);
        }
    }
}

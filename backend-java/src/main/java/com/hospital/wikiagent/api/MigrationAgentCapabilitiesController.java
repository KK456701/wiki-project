package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.evidence.EvidenceEnvelope;
import com.hospital.wikiagent.agent.evidence.EvidenceVerifier;
import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.PromptCatalog;
import com.hospital.wikiagent.agent.model.SpringAiModelInvoker;
import com.hospital.wikiagent.agent.planning.CapabilitySpecRegistry;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;

@RestController
@RequestMapping("/api/migration/agent")
public class MigrationAgentCapabilitiesController {
    private final HospitalAuthService auth;
    private final AgentModelRegistry models;

    public MigrationAgentCapabilitiesController(HospitalAuthService auth, AgentModelRegistry models) {
        this.auth = auth;
        this.models = models;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        auth.authenticate(BearerTokens.require(authorization));
        return Map.of(
                "status", "shadow_only",
                "default_model", models.defaultModelId(),
                "models", models.listModels(),
                "versions", Map.of(
                        "request_plan", RequestPlan.VERSION,
                        "compiled_plan", CompiledPlanIR.VERSION,
                        "capability_registry", CapabilitySpecRegistry.VERSION,
                        "prompt", PromptCatalog.VERSION,
                        "model_adapter", SpringAiModelInvoker.VERSION,
                        "evidence", EvidenceEnvelope.VERSION,
                        "verifier", EvidenceVerifier.VERSION),
                "automatic_tool_loop", false);
    }
}

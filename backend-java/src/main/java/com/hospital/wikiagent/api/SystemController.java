package com.hospital.wikiagent.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SystemController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "agent_orchestration", "plan_compile_control");
    }

    @GetMapping("/migration/status")
    public Map<String, Object> migrationStatus() {
        return Map.of(
                "phase", "agent_ir_and_gateway_shadow",
                "authority_runtime", "python",
                "java_runtime", "compatibility_shadow",
                "completed", List.of(
                        "agent_contract_v1", "dbhub_mcp_client", "hospital_auth", "rule_read_api",
                        "compiled_plan_ir", "deterministic_dispatch", "policy_tool_gateway"),
                "next", List.of("spring_ai_model_adapters", "evidence_ledger", "agent_shadow_runner"));
    }
}

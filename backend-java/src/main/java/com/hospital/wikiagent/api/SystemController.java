package com.hospital.wikiagent.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.migration.MigrationProperties;

@RestController
@RequestMapping("/api")
public class SystemController {
    private final MigrationProperties migration;

    public SystemController(MigrationProperties migration) {
        this.migration = migration;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "agent_orchestration", "plan_compile_control");
    }

    @GetMapping("/migration/status")
    public Map<String, Object> migrationStatus() {
        return Map.of(
                "phase", migration.javaAuthority() ? "java_authority" : "single_jar_shadow",
                "authority_runtime", migration.getAuthorityRuntime(),
                "java_runtime", migration.mode(),
                "cutover_gate", migration.isCutoverApproved() ? "open" : "closed",
                "readiness_report_id", migration.getReadinessReportId(),
                "completed", List.of(
                        "agent_contract_v1", "dbhub_mcp_client", "hospital_auth", "rule_read_api",
                        "compiled_plan_ir", "deterministic_dispatch", "policy_tool_gateway",
                        "spring_ai_model_adapters", "evidence_ledger", "agent_shadow_runner",
                        "controlled_sql_trial", "diagnosis", "upload_comparison",
                        "detail_export", "compound_runtime", "trace_observability",
                        "implementation_validation_mvp", "metadata_workbench",
                        "terminology_read_workbench", "terminology_admin_workflow",
                        "vue_bundle_in_jar", "indicator_implementation_workflow",
                        "cutover_readiness_gate", "hybrid_indicator_resolution",
                        "rule_change_preview", "semantic_replan",
                        "deterministic_answer_fallback"),
                "next", migration.javaAuthority()
                        ? List.of("stability_observation", "retire_python_fallback_after_window")
                        : List.of("real_environment_readiness_execution", "explicit_contract_cutover"));
    }

    @GetMapping("/migration/readiness")
    public Map<String, Object> readiness() {
        return Map.of(
                "mode", migration.mode(),
                "authority_runtime", migration.getAuthorityRuntime(),
                "cutover_approved", migration.isCutoverApproved(),
                "readiness_report_id", migration.getReadinessReportId(),
                "serving_authority", migration.javaAuthority() && migration.isCutoverApproved());
    }
}

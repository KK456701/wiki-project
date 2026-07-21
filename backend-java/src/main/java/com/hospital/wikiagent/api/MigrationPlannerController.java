package com.hospital.wikiagent.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.model.ModelRequestPlanner;
import com.hospital.wikiagent.agent.model.ModelRequestPlanner.PlannerInput;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanValidator;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping({"/api/migration/agent", "/api/agent"})
public class MigrationPlannerController {
    private final HospitalAuthService auth;
    private final ModelRequestPlanner planner;
    private final PlanValidator validator;
    private final PlanCompiler compiler;

    public MigrationPlannerController(
            HospitalAuthService auth,
            ModelRequestPlanner planner,
            PlanValidator validator,
            PlanCompiler compiler) {
        this.auth = auth;
        this.planner = planner;
        this.validator = validator;
        this.compiler = compiler;
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody PlanRequest request) {
        auth.authenticate(BearerTokens.require(authorization));
        var result = planner.plan(new PlannerInput(
                request.query(), request.modelId(),
                LocalDate.now(ZoneId.of("Asia/Shanghai")),
                request.structuredState(), request.recentHistory()));
        return Map.of(
                "status", "shadow_only",
                "model_id", result.modelId(),
                "repaired", result.repaired(),
                "request_plan", result.plan(),
                "validation", validator.validate(result.plan()),
                "compiled_plan", compiler.compile(result.plan()),
                "tools_executed", false);
    }

    public record PlanRequest(
            @NotBlank @Size(max = 4000) String query,
            String modelId,
            @Size(max = 16000) String structuredState,
            @Size(max = 32000) String recentHistory) {
    }
}

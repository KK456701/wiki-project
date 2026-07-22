package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.ir.CompiledPlanIR;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.planning.AgentStateController;
import com.hospital.wikiagent.agent.planning.ControllerDecision;
import com.hospital.wikiagent.agent.planning.PlanCompiler;
import com.hospital.wikiagent.agent.planning.PlanValidation;
import com.hospital.wikiagent.agent.planning.PlanValidator;
import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;

/**
 * 提供受控计划编译接口，用于调试业务计划而不执行工具。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentPlanController {
    private final HospitalAuthService auth;
    private final PlanValidator validator;
    private final PlanCompiler compiler;
    private final AgentStateController controller;

    public AgentPlanController(
            HospitalAuthService auth,
            PlanValidator validator,
            PlanCompiler compiler,
            AgentStateController controller) {
        this.auth = auth;
        this.validator = validator;
        this.compiler = compiler;
        this.controller = controller;
    }

    @PostMapping("/compile")
    public Map<String, Object> compile(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody RequestPlan plan) {
        auth.authenticate(BearerTokens.require(authorization));
        PlanValidation validation = validator.validate(plan);
        CompiledPlanIR compiled = compiler.compile(plan);
        AgentRunState state = new AgentRunState();
        state.currentRuleId(plan.targetIndicator().ruleId());
        ControllerDecision firstDecision = controller.nextDecision(compiled, validation, state);
        return Map.of(
                "status", "shadow_only",
                "validation", validation,
                "compiled_plan", compiled,
                "first_decision", firstDecision,
                "tools_executed", false);
    }
}

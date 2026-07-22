package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.agent.trace.AgentTraceService.AgentTraceNotFoundException;
import com.hospital.wikiagent.agent.trace.AgentTraceService.RunFilters;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;

/**
 * 提供 {@code AgentTraceController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentTraceController {
    private final HospitalAuthService auth;
    private final AgentTraceService traces;

    public AgentTraceController(HospitalAuthService auth, AgentTraceService traces) {
        this.auth = auth;
        this.traces = traces;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "started_after", required = false) String startedAfter,
            @RequestParam(name = "started_before", required = false) String startedBefore,
            @RequestParam(required = false) String status,
            @RequestParam(name = "model_id", required = false) String modelId,
            @RequestParam(name = "tool_name", required = false) String toolName,
            @RequestParam(name = "failure_class", required = false) String failureClass,
            @RequestParam(defaultValue = "100") int limit) {
        var principal = auth.authenticate(BearerTokens.require(authorization));
        try {
            return traces.list(principal, filters(
                    startedAfter, startedBefore, status, modelId, toolName, failureClass, limit));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "started_after", required = false) String startedAfter,
            @RequestParam(name = "started_before", required = false) String startedBefore,
            @RequestParam(required = false) String status,
            @RequestParam(name = "model_id", required = false) String modelId,
            @RequestParam(name = "tool_name", required = false) String toolName,
            @RequestParam(name = "failure_class", required = false) String failureClass) {
        var principal = auth.authenticate(BearerTokens.require(authorization));
        try {
            return traces.metrics(principal, filters(
                    startedAfter, startedBefore, status, modelId, toolName, failureClass, 500));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/{traceId}")
    public Map<String, Object> get(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String traceId) {
        var principal = auth.authenticate(BearerTokens.require(authorization));
        try {
            return traces.get(traceId, principal);
        } catch (AgentTraceNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private static RunFilters filters(
            String after, String before, String status, String modelId,
            String toolName, String failureClass, int limit) {
        return new RunFilters(
                AgentTraceService.parseTime(after), AgentTraceService.parseTime(before),
                status, modelId, toolName, failureClass, limit);
    }
}

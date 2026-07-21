package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.agent.trace.AgentTraceService.AgentTraceNotFoundException;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;

@RestController
@RequestMapping("/api/agent/runs")
public class AgentTraceController {
    private final HospitalAuthService auth;
    private final AgentTraceService traces;

    public AgentTraceController(HospitalAuthService auth, AgentTraceService traces) {
        this.auth = auth;
        this.traces = traces;
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
}

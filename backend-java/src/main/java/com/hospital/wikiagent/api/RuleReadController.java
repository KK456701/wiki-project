package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.rules.RuleNotFoundException;
import com.hospital.wikiagent.rules.RuleReadRepository;

import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/kb/rules")
public class RuleReadController {
    private final HospitalAuthService authService;
    private final RuleReadRepository repository;

    public RuleReadController(HospitalAuthService authService, RuleReadRepository repository) {
        this.authService = authService;
        this.repository = repository;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit) {
        HospitalPrincipal principal = principal(authorization);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return repository.searchForHospital(query, principal.hospitalId(), safeLimit);
    }

    @GetMapping("/{ruleId}/effective")
    public Map<String, Object> effective(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String ruleId,
            @RequestParam(name = "hospital_id", required = false) String requestedHospitalId) {
        HospitalPrincipal principal = principal(authorization);
        if (requestedHospitalId != null && !principal.canAccessHospital(requestedHospitalId)) {
            throw new HospitalAuthException(
                    "不能访问其他医院的指标规则",
                    "AUTH_HOSPITAL_SCOPE_DENIED",
                    HttpStatus.FORBIDDEN);
        }
        return repository.effectiveRule(ruleId, principal.hospitalId());
    }

    private HospitalPrincipal principal(String authorization) {
        return authService.authenticate(BearerTokens.require(authorization));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuleNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", exception.getMessage()));
    }
}

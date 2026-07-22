package com.hospital.wikiagent.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.implementation.ImplementationException;
import com.hospital.wikiagent.implementation.IndicatorDraftRepository;

@RestController
@RequestMapping("/api/indicator-drafts")
/**
 * 提供 {@code IndicatorDraftController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 */
public class IndicatorDraftController {
    private final HospitalAuthService hospitals;
    private final IndicatorDraftRepository drafts;

    public IndicatorDraftController(HospitalAuthService hospitals, IndicatorDraftRepository drafts) {
        this.hospitals = hospitals;
        this.drafts = drafts;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("hospital_id") String hospitalId,
            @RequestParam(value = "status", required = false) String status) {
        HospitalPrincipal principal = authorize(authorization, hospitalId);
        return drafts.list(principal.hospitalId(), status);
    }

    @GetMapping("/{draftId}")
    public Map<String, Object> get(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId) {
        HospitalPrincipal principal = authenticate(authorization);
        return drafts.get(draftId, principal.hospitalId()).orElseThrow(() -> new ImplementationException(
                "DRAFT_NOT_FOUND", "指标实施任务不存在。", 404));
    }

    @GetMapping("/{draftId}/versions")
    public Map<String, Object> versions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId) {
        HospitalPrincipal principal = authenticate(authorization);
        return Map.of("items", drafts.versions(draftId, principal.hospitalId()));
    }

    @PutMapping("/{draftId}")
    public Map<String, Object> update(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId, @RequestBody UpdateRequest request) {
        HospitalPrincipal principal = authenticate(authorization);
        return drafts.update(draftId, principal.hospitalId(), request.expectedVersion(),
                request.changes(), principal.userId());
    }

    @PostMapping("/{draftId}/requirements-confirm")
    public Map<String, Object> confirmRequirements(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId, @RequestBody ActionRequest request) {
        HospitalPrincipal principal = authenticate(authorization);
        return drafts.confirmRequirements(draftId, principal.hospitalId(), request.expectedVersion(),
                principal.userId());
    }

    @PostMapping("/{draftId}/submit")
    public Map<String, Object> submit(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId, @RequestBody ActionRequest request) {
        HospitalPrincipal principal = authenticate(authorization);
        return drafts.submit(draftId, principal.hospitalId(), request.expectedVersion(), principal.userId());
    }

    private HospitalPrincipal authorize(String authorization, String hospitalId) {
        HospitalPrincipal principal = authenticate(authorization);
        if (hospitalId == null || hospitalId.isBlank() || !principal.canAccessHospital(hospitalId)) {
            throw new HospitalAuthException("不能访问其他医院的指标实施任务。",
                    "AUTH_HOSPITAL_SCOPE_DENIED", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    private HospitalPrincipal authenticate(String authorization) {
        return hospitals.authenticate(BearerTokens.require(authorization));
    }

    @ExceptionHandler(ImplementationException.class)
    public ResponseEntity<Map<String, Object>> implementation(ImplementationException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("detail", exception.getMessage(), "code", exception.code()));
    }

    public record UpdateRequest(
            @JsonProperty("expected_version") int expectedVersion,
            Map<String, Object> changes,
            @JsonProperty("actor_id") String actorId) { }

    public record ActionRequest(
            @JsonProperty("expected_version") int expectedVersion,
            @JsonProperty("actor_id") String actorId) { }
}

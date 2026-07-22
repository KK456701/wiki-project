package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hospital.wikiagent.auth.AdminSessionService;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.implementation.ImplementationException;
import com.hospital.wikiagent.implementation.IndicatorDraftPublisher;

/**
 * 提供 {@code IndicatorGovernanceController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@RestController
@RequestMapping("/api")
public class IndicatorGovernanceController {
    private final AdminSessionService admins;
    private final HospitalAuthService hospitals;
    private final IndicatorDraftPublisher publisher;

    public IndicatorGovernanceController(
            AdminSessionService admins, HospitalAuthService hospitals, IndicatorDraftPublisher publisher) {
        this.admins = admins;
        this.hospitals = hospitals;
        this.publisher = publisher;
    }

    @PostMapping("/indicator-drafts/{draftId}/approve")
    public Map<String, Object> approve(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String adminAuthorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String draftId, @RequestBody ApprovalRequest request) {
        HospitalPrincipal principal = authorize(adminAuthorization, hospitalAuthorization, request.hospitalId());
        return publisher.approve(draftId, principal.hospitalId(), request.expectedVersion(), "admin");
    }

    @PostMapping("/indicator-drafts/{draftId}/reject")
    public Map<String, Object> reject(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String adminAuthorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String draftId, @RequestBody ApprovalRequest request) {
        HospitalPrincipal principal = authorize(adminAuthorization, hospitalAuthorization, request.hospitalId());
        return publisher.reject(draftId, principal.hospitalId(), request.expectedVersion(),
                "admin", request.reason());
    }

    @GetMapping("/hospital-defined/{hospitalId}/{indexCode}/versions")
    public Map<String, Object> versions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String adminAuthorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String hospitalId, @PathVariable String indexCode) {
        authorize(adminAuthorization, hospitalAuthorization, hospitalId);
        return publisher.listVersions(hospitalId, indexCode);
    }

    @PostMapping("/hospital-defined/{hospitalId}/{indexCode}/restore")
    public Map<String, Object> restore(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String adminAuthorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String hospitalId, @PathVariable String indexCode,
            @RequestBody RestoreRequest request) {
        authorize(adminAuthorization, hospitalAuthorization, hospitalId);
        return publisher.restore(hospitalId, indexCode, request.version(), "admin");
    }

    private HospitalPrincipal authorize(
            String adminAuthorization, String hospitalAuthorization, String hospitalId) {
        admins.require(adminAuthorization);
        HospitalPrincipal principal = hospitals.authenticate(BearerTokens.require(hospitalAuthorization));
        if (hospitalId == null || hospitalId.isBlank() || !principal.canAccessHospital(hospitalId)) {
            throw new HospitalAuthException("不能管理其他医院的指标规则。",
                    "AUTH_HOSPITAL_SCOPE_DENIED", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    @ExceptionHandler(ImplementationException.class)
    public ResponseEntity<Map<String, Object>> implementation(ImplementationException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("detail", exception.getMessage(), "code", exception.code()));
    }

    public record ApprovalRequest(
            @JsonProperty("hospital_id") String hospitalId,
            @JsonProperty("expected_version") int expectedVersion,
            String reason) { }
    public record RestoreRequest(int version) { }
}

package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.AdminSessionService;
import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.terminology.TerminologyService;
import com.hospital.wikiagent.terminology.TerminologyGovernanceException;
import com.hospital.wikiagent.terminology.TerminologyGovernanceService;
import com.hospital.wikiagent.terminology.TerminologyGovernanceService.AliasCommand;
import com.hospital.wikiagent.terminology.TerminologyGovernanceService.MappingCommand;
import com.hospital.wikiagent.terminology.TerminologyService.TerminologyNotFoundException;

/**
 * 提供 {@code TerminologyController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@RestController
@RequestMapping("/api/terminology")
public class TerminologyController {
    private final HospitalAuthService auth;
    private final TerminologyService terminology;
    private final AdminSessionService admins;
    private final TerminologyGovernanceService governance;

    public TerminologyController(
            HospitalAuthService auth, TerminologyService terminology,
            AdminSessionService admins, TerminologyGovernanceService governance) {
        this.auth = auth;
        this.terminology = terminology;
        this.admins = admins;
        this.governance = governance;
    }

    @GetMapping("/concepts")
    public Map<String, Object> concepts(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(name = "concept_type", defaultValue = "") String conceptType,
            @RequestParam(name = "rule_id", defaultValue = "") String ruleId) {
        principal(authorization);
        return terminology.listConcepts(query, conceptType, ruleId);
    }

    @GetMapping("/concepts/{conceptCode}")
    public Map<String, Object> concept(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String conceptCode,
            @RequestParam(name = "hospital_id", required = false) String hospitalId) {
        HospitalPrincipal principal = principal(authorization);
        requireHospital(principal, hospitalId);
        return terminology.concept(conceptCode, principal.hospitalId());
    }

    @PostMapping("/test")
    public Map<String, Object> test(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody RecognitionRequest request) {
        HospitalPrincipal principal = principal(authorization);
        requireHospital(principal, request.hospitalId());
        String text = request.text() == null ? "" : request.text().strip();
        if (text.isEmpty() || text.length() > 1000) {
            throw new IllegalArgumentException("识别文本长度必须为 1 至 1000 个字符。");
        }
        return terminology.normalize(text, principal.hospitalId());
    }

    @GetMapping("/releases")
    public Map<String, Object> releases(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        principal(authorization);
        return Map.of("items", terminology.releases());
    }

    @PostMapping("/aliases")
    public Map<String, Object> createAlias(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @RequestBody AliasRequest request) {
        admins.require(authorization);
        String hospital = request.hospitalId() == null ? "" : request.hospitalId().strip();
        if (!hospital.isEmpty()) requireHospital(principal(hospitalAuthorization), hospital);
        return governance.createAlias(new AliasCommand(
                hospital, request.conceptCode(), request.aliasText(), request.relationType(),
                request.retrievalEnabled() == null || request.retrievalEnabled(),
                Boolean.TRUE.equals(request.sqlSafe()), request.ambiguityGroup(),
                request.sourceReference()));
    }

    @PostMapping("/aliases/{aliasId}/approve")
    public Map<String, Object> approveAlias(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable long aliasId, @RequestBody ActorRequest ignored) {
        admins.require(authorization);
        String hospital = hospitalAuthorization == null ? "" : principal(hospitalAuthorization).hospitalId();
        return governance.approveAlias(aliasId, hospital);
    }

    @PostMapping("/hospital-mappings")
    public Map<String, Object> createMapping(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @RequestBody MappingRequest request) {
        admins.require(authorization);
        HospitalPrincipal principal = principal(hospitalAuthorization);
        requireHospital(principal, request.hospitalId());
        return governance.createMapping(new MappingCommand(
                request.hospitalId(), request.conceptCode(), request.codeSystem(), request.localCode(),
                request.localName(), request.localValue(), request.effectiveFrom(), request.effectiveTo()),
                principal.hospitalId());
    }

    @PostMapping("/hospital-mappings/{mappingId}/approve")
    public Map<String, Object> approveMapping(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable long mappingId, @RequestBody ActorRequest ignored) {
        admins.require(authorization);
        HospitalPrincipal principal = principal(hospitalAuthorization);
        return governance.approveMapping(mappingId, principal.hospitalId());
    }

    @PostMapping("/releases/publish")
    public Map<String, Object> publish(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody ActorRequest ignored) {
        admins.require(authorization);
        return governance.publish();
    }

    @PostMapping("/releases/{releaseId}/restore")
    public Map<String, Object> restore(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String releaseId, @RequestBody ActorRequest ignored) {
        admins.require(authorization);
        return governance.restore(releaseId);
    }

    private HospitalPrincipal principal(String authorization) {
        return auth.authenticate(BearerTokens.require(authorization));
    }

    private static void requireHospital(HospitalPrincipal principal, String requested) {
        if (requested != null && !requested.isBlank() && !principal.canAccessHospital(requested)) {
            throw new HospitalAuthException(
                    "不能访问其他医院的术语映射。",
                    "AUTH_HOSPITAL_SCOPE_DENIED", HttpStatus.FORBIDDEN);
        }
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(TerminologyNotFoundException.class)
    public Map<String, Object> notFound(TerminologyNotFoundException exception) {
        return Map.of("detail", exception.getMessage());
    }

    @ExceptionHandler(TerminologyGovernanceException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> governance(
            TerminologyGovernanceException exception) {
        return org.springframework.http.ResponseEntity.status(exception.status())
                .body(Map.of("detail", exception.getMessage(), "code", exception.code()));
    }

    public record RecognitionRequest(
            @JsonProperty("hospital_id") String hospitalId,
            String text) { }

    public record AliasRequest(
            @JsonProperty("hospital_id") String hospitalId,
            @JsonProperty("concept_code") String conceptCode,
            @JsonProperty("alias_text") String aliasText,
            @JsonProperty("relation_type") String relationType,
            @JsonProperty("retrieval_enabled") Boolean retrievalEnabled,
            @JsonProperty("sql_safe") Boolean sqlSafe,
            @JsonProperty("ambiguity_group") String ambiguityGroup,
            @JsonProperty("source_reference") String sourceReference) { }

    public record MappingRequest(
            @JsonProperty("hospital_id") String hospitalId,
            @JsonProperty("concept_code") String conceptCode,
            @JsonProperty("code_system") String codeSystem,
            @JsonProperty("local_code") String localCode,
            @JsonProperty("local_name") String localName,
            @JsonProperty("local_value") String localValue,
            @JsonProperty("effective_from") String effectiveFrom,
            @JsonProperty("effective_to") String effectiveTo) { }

    public record ActorRequest(@JsonProperty("actor_id") String actorId) { }
}

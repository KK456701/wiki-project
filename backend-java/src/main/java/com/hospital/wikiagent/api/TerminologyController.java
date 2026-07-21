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
import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.terminology.TerminologyService;
import com.hospital.wikiagent.terminology.TerminologyService.TerminologyNotFoundException;

@RestController
@RequestMapping("/api/terminology")
public class TerminologyController {
    private final HospitalAuthService auth;
    private final TerminologyService terminology;

    public TerminologyController(HospitalAuthService auth, TerminologyService terminology) {
        this.auth = auth;
        this.terminology = terminology;
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

    public record RecognitionRequest(
            @JsonProperty("hospital_id") String hospitalId,
            String text) { }
}

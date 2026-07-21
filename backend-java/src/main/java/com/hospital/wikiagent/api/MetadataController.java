package com.hospital.wikiagent.api;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.metadata.MetadataSyncService;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {
    private final HospitalAuthService auth;
    private final MetadataSyncService metadata;

    public MetadataController(HospitalAuthService auth, MetadataSyncService metadata) {
        this.auth = auth;
        this.metadata = metadata;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "hospital_id", required = false) String hospitalId,
            @RequestParam(name = "db_name", required = false) String databaseName) {
        HospitalPrincipal principal = principal(authorization);
        requireHospital(principal, hospitalId);
        return metadata.overview(principal, databaseName);
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody MetadataSyncRequest request) {
        HospitalPrincipal principal = principal(authorization);
        requireHospital(principal, request.hospitalId());
        return metadata.sync(
                principal, request.hospitalId(), request.databaseName(), request.source());
    }

    private HospitalPrincipal principal(String authorization) {
        return auth.authenticate(BearerTokens.require(authorization));
    }

    private static void requireHospital(HospitalPrincipal principal, String requested) {
        if (requested != null && !requested.isBlank() && !principal.canAccessHospital(requested)) {
            throw new HospitalAuthException(
                    "不能访问其他医院的数据库元数据。",
                    "AUTH_HOSPITAL_SCOPE_DENIED", HttpStatus.FORBIDDEN);
        }
    }

    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    @ExceptionHandler(DbHubMcpException.class)
    public Map<String, Object> dbHubError(DbHubMcpException exception) {
        return Map.of("detail", "DBHub 元数据同步失败: " + exception.getMessage());
    }

    public record MetadataSyncRequest(
            @JsonProperty("hospital_id") String hospitalId,
            @JsonProperty("db_name") String databaseName,
            String source) {
    }
}

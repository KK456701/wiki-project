package com.hospital.wikiagent.api;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.sql.SqlPreviewService;
import com.hospital.wikiagent.agent.sql.SqlPreviewService.PreviewRequest;
import com.hospital.wikiagent.agent.sql.SqlPreviewService.PreviewResult;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Authenticated HTTP boundary for bounded read-only SQL previews. */
@RestController
@RequestMapping("/api/sql-executions")
public class SqlPreviewController {
    private final HospitalAuthService auth;
    private final SqlPreviewService previews;

    public SqlPreviewController(HospitalAuthService auth, SqlPreviewService previews) {
        this.auth = auth;
        this.previews = previews;
    }

    @PostMapping("/preview")
    public PreviewResult preview(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody Request request) {
        return previews.execute(auth.authenticate(BearerTokens.require(authorization)),
                new PreviewRequest(request.sql(), request.databaseRole(), request.ruleId(),
                        request.profileId(), request.statStart(), request.statEnd()));
    }

    public record Request(
            @NotBlank @Size(max = 200000) String sql,
            @NotBlank @Pattern(regexp = "^(BUSINESS|REAL)$") String databaseRole,
            @NotBlank @Pattern(regexp = "^HXZD-[0-9]{3}-[0-9]{3}$") String ruleId,
            @Size(max = 128) String profileId,
            @Size(max = 32) String statStart,
            @Size(max = 32) String statEnd) {}
}

package com.hospital.wikiagent.api;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/auth/hospital")
public class HospitalAuthController {
    private final HospitalAuthService service;

    public HospitalAuthController(HospitalAuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body) {
        return response(service.login(body.accountId(), body.password()));
    }

    @PostMapping("/change-password")
    public LoginResponse changePassword(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody ChangePasswordRequest body) {
        HospitalPrincipal principal = service.authenticate(BearerTokens.require(authorization));
        return response(service.changePassword(principal, body.currentPassword(), body.newPassword()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        HospitalPrincipal principal = service.authenticate(BearerTokens.require(authorization));
        service.logout(principal);
        return ResponseEntity.noContent().build();
    }

    private static LoginResponse response(HospitalAuthService.LoginResult result) {
        List<String> permissions = result.permissions().stream().sorted(Comparator.naturalOrder()).toList();
        return new LoginResponse(
                result.token(),
                "bearer",
                result.expiresAt(),
                result.userId(),
                result.accountId(),
                result.hospitalId(),
                permissions,
                result.mustChangePassword());
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String accountId,
            @NotBlank @Size(max = 256) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(min = 8, max = 256) String newPassword) {
    }

    public record LoginResponse(
            String token,
            String tokenType,
            LocalDateTime expiresAt,
            String userId,
            String accountId,
            String hospitalId,
            List<String> permissions,
            boolean mustChangePassword) {
    }
}

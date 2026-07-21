package com.hospital.wikiagent.api;

import java.time.LocalDateTime;
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
import com.hospital.wikiagent.auth.AdminSessionService;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthException;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.monitoring.MonitoringException;
import com.hospital.wikiagent.monitoring.MonitoringRepository;
import com.hospital.wikiagent.monitoring.MonitoringService;
import com.hospital.wikiagent.monitoring.MonitoringService.PlanCommand;

@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {
    private final AdminSessionService admins;
    private final HospitalAuthService hospitals;
    private final MonitoringRepository repository;
    private final MonitoringService service;

    public MonitoringController(AdminSessionService admins, HospitalAuthService hospitals,
            MonitoringRepository repository, MonitoringService service) {
        this.admins = admins;
        this.hospitals = hospitals;
        this.repository = repository;
        this.service = service;
    }

    @PostMapping("/plans")
    public Map<String, Object> createPlan(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @RequestBody PlanRequest request) {
        HospitalPrincipal principal = authorize(authorization, hospitalAuthorization, request.hospitalId());
        return service.create(request.command(), principal.userId());
    }

    @GetMapping("/plans")
    public Map<String, Object> listPlans(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @RequestParam("hospital_id") String hospitalId) {
        authorize(authorization, hospitalAuthorization, hospitalId);
        return Map.of("items", repository.listPlans(hospitalId));
    }

    @PutMapping("/plans/{planId}")
    public Map<String, Object> updatePlan(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String planId, @RequestBody PlanRequest request) {
        authorize(authorization, hospitalAuthorization, request.hospitalId());
        return service.update(planId, request.hospitalId(), request.command());
    }

    @PostMapping("/plans/{planId}/enable")
    public Map<String, Object> enablePlan(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String planId, @RequestParam("hospital_id") String hospitalId) {
        authorize(authorization, hospitalAuthorization, hospitalId);
        return service.status(planId, hospitalId, "enabled");
    }

    @PostMapping("/plans/{planId}/disable")
    public Map<String, Object> disablePlan(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String planId, @RequestParam("hospital_id") String hospitalId) {
        authorize(authorization, hospitalAuthorization, hospitalId);
        return service.status(planId, hospitalId, "disabled");
    }

    @GetMapping("/results")
    public Map<String, Object> listResults(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @RequestParam("hospital_id") String hospitalId,
            @RequestParam(value = "rule_id", required = false) String ruleId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        authorize(authorization, hospitalAuthorization, hospitalId);
        return Map.of("items", repository.listResults(hospitalId, ruleId, bounded(limit)));
    }

    @GetMapping("/results/{resultId}")
    public Map<String, Object> result(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable long resultId, @RequestParam("hospital_id") String hospitalId) {
        authorize(authorization, hospitalAuthorization, hospitalId);
        return repository.result(resultId, hospitalId);
    }

    @GetMapping("/alerts")
    public Map<String, Object> listAlerts(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @RequestParam("hospital_id") String hospitalId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        authorize(authorization, hospitalAuthorization, hospitalId);
        return Map.of("items", repository.listAlerts(hospitalId, status, bounded(limit)));
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    public Map<String, Object> acknowledge(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String alertId, @RequestBody AlertActionRequest request) {
        HospitalPrincipal principal = authorize(authorization, hospitalAuthorization, request.hospitalId());
        return repository.updateAlert(alertId, request.hospitalId(), "acknowledged", principal.userId(),
                LocalDateTime.now().withNano(0));
    }

    @PostMapping("/alerts/{alertId}/close")
    public Map<String, Object> close(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Hospital-Authorization", required = false) String hospitalAuthorization,
            @PathVariable String alertId, @RequestBody AlertActionRequest request) {
        HospitalPrincipal principal = authorize(authorization, hospitalAuthorization, request.hospitalId());
        return repository.updateAlert(alertId, request.hospitalId(), "closed", principal.userId(),
                LocalDateTime.now().withNano(0));
    }

    private HospitalPrincipal authorize(String adminAuthorization, String hospitalAuthorization, String hospitalId) {
        admins.require(adminAuthorization);
        HospitalPrincipal principal = hospitals.authenticate(BearerTokens.require(hospitalAuthorization));
        if (hospitalId == null || hospitalId.isBlank() || !principal.canAccessHospital(hospitalId)) {
            throw new HospitalAuthException("不能访问其他医院的监控数据。",
                    "AUTH_HOSPITAL_SCOPE_DENIED", org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    private static int bounded(int limit) { return Math.min(Math.max(limit, 1), 500); }

    @ExceptionHandler(MonitoringException.class)
    public ResponseEntity<Map<String, Object>> monitoring(MonitoringException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("detail", exception.getMessage(), "code", exception.code()));
    }

    public record PlanRequest(
            @JsonProperty("plan_id") String planId,
            @JsonProperty("hospital_id") String hospitalId,
            @JsonProperty("rule_id") String ruleId,
            @JsonProperty("plan_name") String planName,
            String frequency,
            @JsonProperty("run_time") String runTime,
            @JsonProperty("day_of_month") Integer dayOfMonth,
            String timezone,
            @JsonProperty("mom_enabled") Boolean momEnabled,
            @JsonProperty("mom_threshold_pct") Double momThresholdPct,
            @JsonProperty("yoy_enabled") Boolean yoyEnabled,
            @JsonProperty("yoy_threshold_pct") Double yoyThresholdPct,
            @JsonProperty("created_by") String createdBy) {
        PlanCommand command() {
            return new PlanCommand(planId, hospitalId, ruleId, planName, frequency, runTime, dayOfMonth,
                    timezone, momEnabled, momThresholdPct, yoyEnabled, yoyThresholdPct);
        }
    }

    public record AlertActionRequest(
            @JsonProperty("hospital_id") String hospitalId,
            @JsonProperty("actor_id") String actorId) { }
}

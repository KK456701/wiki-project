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
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.implementation.DraftMetadataService;
import com.hospital.wikiagent.implementation.DraftWorkflowService;
import com.hospital.wikiagent.implementation.ImplementationException;

/**
 * 提供 {@code IndicatorDraftWorkflowController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@RestController
@RequestMapping("/api/indicator-drafts")
public class IndicatorDraftWorkflowController {
    private final HospitalAuthService hospitals;
    private final DraftMetadataService metadata;
    private final DraftWorkflowService workflow;

    public IndicatorDraftWorkflowController(
            HospitalAuthService hospitals, DraftMetadataService metadata, DraftWorkflowService workflow) {
        this.hospitals = hospitals;
        this.metadata = metadata;
        this.workflow = workflow;
    }

    @GetMapping("/{draftId}/metadata-suggestions")
    public Map<String, Object> suggestions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId) {
        HospitalPrincipal principal = authenticate(authorization);
        return metadata.suggestions(draftId, principal.hospitalId());
    }

    @PostMapping("/{draftId}/metadata-confirm")
    public Map<String, Object> confirmMetadata(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId, @RequestBody MetadataConfirmRequest request) {
        HospitalPrincipal principal = authenticate(authorization);
        return metadata.confirm(draftId, principal.hospitalId(), request.expectedVersion(),
                request.mappings(), principal.userId());
    }

    @PostMapping("/{draftId}/sql-generate")
    public Map<String, Object> generateSql(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId, @RequestBody VersionRequest request) {
        HospitalPrincipal principal = authenticate(authorization);
        return workflow.generateSql(draftId, principal.hospitalId(), request.expectedVersion(), principal.userId());
    }

    @PostMapping("/{draftId}/trial-run")
    public Map<String, Object> trialRun(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String draftId, @RequestBody TrialRunRequest request) {
        HospitalPrincipal principal = authenticate(authorization);
        return workflow.trialRun(draftId, principal.hospitalId(), request.expectedVersion(),
                request.statStartTime(), request.statEndTime(), principal.userId());
    }

    private HospitalPrincipal authenticate(String authorization) {
        return hospitals.authenticate(BearerTokens.require(authorization));
    }

    @ExceptionHandler(ImplementationException.class)
    public ResponseEntity<Map<String, Object>> implementation(ImplementationException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("detail", exception.getMessage(), "code", exception.code()));
    }

    public record VersionRequest(@JsonProperty("expected_version") int expectedVersion) { }
    public record MetadataConfirmRequest(
            @JsonProperty("expected_version") int expectedVersion,
            Map<String, Map<String, Object>> mappings) { }
    public record TrialRunRequest(
            @JsonProperty("expected_version") int expectedVersion,
            @JsonProperty("stat_start_time") String statStartTime,
            @JsonProperty("stat_end_time") String statEndTime) { }
}

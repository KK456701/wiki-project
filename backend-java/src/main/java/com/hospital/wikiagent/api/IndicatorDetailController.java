package com.hospital.wikiagent.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.DetailContracts.DetailPage;
import com.hospital.wikiagent.details.DetailContracts.ExportSummary;
import com.hospital.wikiagent.details.DetailContracts.SnapshotSummary;
import com.hospital.wikiagent.details.DiagnosisReportExportService;
import com.hospital.wikiagent.details.IndicatorDetailService;
import com.hospital.wikiagent.details.UploadComparisonExportService;

/**
 * 提供 {@code IndicatorDetailController} 对应的 HTTP 接口，并保持鉴权与业务编排边界。
 *
 * <p>控制器只负责请求校验、登录主体解析和响应映射，实际规则解析、SQL 生成及数据访问委托给领域服务。医院范围始终来自已认证主体，不能被客户端参数覆盖。</p>
 */
@RestController
public class IndicatorDetailController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final HospitalAuthService auth;
    private final IndicatorDetailService details;
    private final UploadComparisonExportService comparisonExports;
    private final DiagnosisReportExportService diagnosisExports;

    public IndicatorDetailController(
            HospitalAuthService auth,
            IndicatorDetailService details,
            UploadComparisonExportService comparisonExports,
            DiagnosisReportExportService diagnosisExports) {
        this.auth = auth;
        this.details = details;
        this.comparisonExports = comparisonExports;
        this.diagnosisExports = diagnosisExports;
    }

    @PostMapping("/api/sql-runs/{run_id}/details")
    public ResponseEntity<SnapshotSummary> ensureDetails(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("run_id") String runId) {
        SnapshotSummary result = details.ensureSnapshot(principal(authorization), runId);
        return ResponseEntity.status(result.reused() ? 200 : 201)
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    @GetMapping("/api/sql-runs/{run_id}/details/{group}")
    public ResponseEntity<DetailPage> page(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("run_id") String runId,
            @PathVariable String group,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        DetailPage result = details.getPage(principal(authorization), runId, group, page,
                pageSize == null ? details.defaultPageSize() : pageSize);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/api/sql-runs/{run_id}/exports")
    public ResponseEntity<ExportSummary> createExport(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("run_id") String runId,
            @RequestBody(required = false) ExportCreateRequest request) {
        boolean confirmed = request != null && request.confirmed();
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(details.createExport(principal(authorization), runId, confirmed));
    }

    @PostMapping("/api/sql-runs/{run_id}/upload-comparison-exports")
    public ResponseEntity<ExportSummary> createUploadComparisonExport(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("run_id") String runId,
            @RequestBody(required = false) UploadComparisonExportCreateRequest request) {
        boolean confirmed = request != null && request.confirmed();
        String fileToken = request == null ? null : request.fileToken();
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(comparisonExports.create(
                        principal(authorization), runId, fileToken, confirmed));
    }

    /**
     * 根据差异诊断报告生成受权限保护的 Excel。请求体只需要用户确认标志，
     * 文件编号、试运行编号和医院范围均从服务端报告对象解析。
     */
    @PostMapping("/api/diagnosis-reports/{report_id}/exports")
    public ResponseEntity<ExportSummary> createDiagnosisExport(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("report_id") String reportId,
            @RequestBody(required = false) ExportCreateRequest request) {
        boolean confirmed = request != null && request.confirmed();
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(diagnosisExports.create(
                        principal(authorization), reportId, confirmed));
    }

    @GetMapping("/api/indicator-exports")
    public ResponseEntity<List<ExportSummary>> exports(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(details.listExports(principal(authorization)));
    }

    @GetMapping("/api/indicator-exports/{export_id}/download")
    public ResponseEntity<FileSystemResource> download(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable("export_id") String exportId) {
        var file = details.resolveDownload(principal(authorization), exportId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file.path()));
    }

    private HospitalPrincipal principal(String authorization) {
        return auth.authenticate(BearerTokens.require(authorization));
    }

    public record ExportCreateRequest(boolean confirmed) {
    }

    public record UploadComparisonExportCreateRequest(boolean confirmed, String fileToken) {
    }
}

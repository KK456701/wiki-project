package com.hospital.wikiagent.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.report.BatchReportService;
import com.hospital.wikiagent.report.BatchReportService.Download;

/**
 * 版本化批次报告快照及 Word、PDF、Excel 下载接口。
 *
 * <p>控制器只接受已鉴权的批次或报告标识，并把医院与用户边界交给报告服务校验；不接受任意文件路径、
 * SQL 或客户端拼装的报告内容。所有格式均从同一份不可变快照生成，确保预览与下载内容一致。</p>
 */
@RestController
@RequestMapping("/api")
public class BatchReportController {
    private final HospitalAuthService auth;
    private final BatchReportService reports;

    public BatchReportController(HospitalAuthService auth, BatchReportService reports) {
        this.auth = auth;
        this.reports = reports;
    }

    @PostMapping("/batch-runs/{batchRunId}/reports")
    public Map<String, Object> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable String batchRunId) {
        return reports.createSnapshot(principal(authorization), batchRunId);
    }

    @GetMapping("/batch-reports/{reportId}")
    public Map<String, Object> load(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable String reportId) {
        return reports.load(principal(authorization), reportId);
    }

    @GetMapping("/batch-reports/{reportId}/download")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorization,
            @PathVariable String reportId,
            @RequestParam String format) {
        Download file = reports.download(principal(authorization), reportId, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build().toString())
                .contentLength(file.bytes().length)
                .body(file.bytes());
    }

    private HospitalPrincipal principal(String authorization) {
        return auth.authenticate(BearerTokens.require(authorization));
    }
}

package com.hospital.wikiagent.details;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.diagnosis.DiagnosisReportRepository;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.DetailContracts.ExportSummary;

/**
 * 把差异诊断报告安全引用转换为现有 Excel 导出流程。
 *
 * <p>报告本身不保存患者行：有逐条上传文件时复用上传对比导出，生成双方都有、仅系统有、
 * 仅文件有及字段差异；没有上传明细时只导出当前系统分子/分母明细。下载仍复用统一
 * {@code /api/indicator-exports/{export_id}/download}，不建立第二套文件权限模型。</p>
 */
@Service
public class DiagnosisReportExportService {
    private static final Pattern SAFE_REPORT_ID = Pattern.compile("DDR_[A-Za-z0-9_-]{1,64}");

    private final DiagnosisReportRepository reports;
    private final IndicatorDetailService details;
    private final UploadComparisonExportService comparisonExports;

    public DiagnosisReportExportService(
            DiagnosisReportRepository reports,
            IndicatorDetailService details,
            UploadComparisonExportService comparisonExports) {
        this.reports = reports;
        this.details = details;
        this.comparisonExports = comparisonExports;
    }

    public ExportSummary create(
            HospitalPrincipal principal,
            String reportId,
            boolean confirmed) {
        String normalized = reportId == null ? "" : reportId.strip();
        if (!SAFE_REPORT_ID.matcher(normalized).matches()) {
            throw error("DIAGNOSIS_REPORT_ID_INVALID", "诊断报告编号无效。", HttpStatus.BAD_REQUEST);
        }
        var stored = reports.find(normalized, principal.hospitalId())
                .orElseThrow(() -> error(
                        "DIAGNOSIS_REPORT_NOT_FOUND", "诊断报告不存在。",
                        HttpStatus.NOT_FOUND));
        Map<String, Object> payload = stored.payload();
        String runId = text(payload.get("baseline_run_id"));
        if (runId.isBlank()) {
            throw error(
                    "DIAGNOSIS_EXPORT_UNAVAILABLE",
                    "本报告没有可导出的系统试运行明细。",
                    HttpStatus.CONFLICT);
        }
        String fileKey = text(payload.get("file_key"));
        if (!fileKey.isBlank()) {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    fileKey.getBytes(StandardCharsets.UTF_8));
            return comparisonExports.createForDiagnosis(
                    principal, runId, token, confirmed, payload);
        }
        return details.createExport(principal, runId, confirmed);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static IndicatorDetailException error(
            String code,
            String message,
            HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }
}

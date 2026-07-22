package com.hospital.wikiagent.details;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.auth.HospitalAuthRepository;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.DetailContracts.ExportSummary;
import com.hospital.wikiagent.details.IndicatorDetailRepository.ExportRecord;
import com.hospital.wikiagent.details.IndicatorDetailRepository.SnapshotRecord;
import com.hospital.wikiagent.details.UploadDetailComparator.RowComparison;
import com.hospital.wikiagent.runtime.WorkspacePaths;
import com.hospital.wikiagent.upload.UploadStorage;
import com.hospital.wikiagent.upload.UploadStorage.UploadAccessException;
import com.hospital.wikiagent.upload.XlsxWorkbookReader;

@Service
/**
 * 编排 {@code UploadComparisonExportService} 对应的业务流程，并集中维护事务与安全边界。
 */
public class UploadComparisonExportService {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final IndicatorDetailService details;
    private final IndicatorDetailRepository repository;
    private final UploadStorage uploads;
    private final XlsxWorkbookReader reader;
    private final UploadDetailComparator comparator;
    private final XlsxWorkbookWriter writer;
    private final HospitalAuthRepository auditRepository;
    private final DetailProperties properties;
    private final Clock clock;
    private final Path root;

    @Autowired
    public UploadComparisonExportService(
            IndicatorDetailService details,
            IndicatorDetailRepository repository,
            UploadStorage uploads,
            XlsxWorkbookReader reader,
            UploadDetailComparator comparator,
            XlsxWorkbookWriter writer,
            HospitalAuthRepository auditRepository,
            DetailProperties properties) {
        this(details, repository, uploads, reader, comparator, writer,
                auditRepository, properties, Clock.systemUTC());
    }

    UploadComparisonExportService(
            IndicatorDetailService details,
            IndicatorDetailRepository repository,
            UploadStorage uploads,
            XlsxWorkbookReader reader,
            UploadDetailComparator comparator,
            XlsxWorkbookWriter writer,
            HospitalAuthRepository auditRepository,
            DetailProperties properties,
            Clock clock) {
        this.details = details;
        this.repository = repository;
        this.uploads = uploads;
        this.reader = reader;
        this.comparator = comparator;
        this.writer = writer;
        this.auditRepository = auditRepository;
        this.properties = properties;
        this.clock = clock;
        this.root = WorkspacePaths.resolve(properties.getExportRoot());
    }

    public ExportSummary create(
            HospitalPrincipal principal,
            String runId,
            String fileToken,
            boolean confirmed) {
        requireExportPermission(principal);
        if (!confirmed) {
            throw error("UPLOAD_COMPARISON_EXPORT_CONFIRM_REQUIRED",
                    "导出前必须确认患者明细差异的使用范围。", HttpStatus.BAD_REQUEST);
        }
        String normalizedRunId = safeId(runId, "试运行编号无效");
        String fileKey = decodeFileToken(fileToken);
        UploadStorage.StoredUpload upload;
        try {
            upload = uploads.requireOwned(fileKey, principal.hospitalId());
        } catch (UploadAccessException exception) {
            HttpStatus status = "UPLOAD_ACCESS_DENIED".equals(exception.code())
                    ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND;
            throw error(exception.code(), exception.getMessage(), status);
        }
        var dataset = details.comparisonDataset(principal, normalizedRunId);
        RowComparison comparison = comparator.compare(reader.read(upload), dataset);
        if (!comparison.available()) {
            throw error("UPLOAD_ROW_COMPARISON_UNAVAILABLE", comparison.message(), HttpStatus.CONFLICT);
        }
        SnapshotRecord snapshot = repository.snapshotByRun(normalizedRunId)
                .orElseThrow(() -> error("DETAIL_NOT_FOUND", "明细快照不存在。", HttpStatus.NOT_FOUND));
        String exportId = "EXP_" + compactId();
        String fileName = dataset.summary().ruleId() + "_" + digits(dataset.summary().statStart())
                + "_" + digits(dataset.summary().statEnd()) + "_逐条差异_" + exportId + ".xlsx";
        String relativePath = safeId(principal.hospitalId(), "医院编号无效") + "/"
                + normalizedRunId + "/" + fileName;
        Instant now = clock.instant();
        int rowCount = comparison.bothCount() + comparison.systemOnlyCount()
                + comparison.uploadedOnlyCount();
        repository.createExport(exportId, snapshot, relativePath, fileName, rowCount,
                principal.userId(), now,
                now.plusSeconds(Math.max(1, properties.getExpireHours()) * 3600L));
        Path target = resolveOwned(relativePath);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            rejectSymlink(target.getParent());
            writer.writeUploadComparisonWorkbook(
                    temporary, comparison, upload.originalName(), principal.hospitalId(),
                    principal.accountId(), now);
            move(temporary, target);
            repository.markExportReady(exportId, sha256(target));
            ExportRecord record = repository.export(exportId)
                    .orElseThrow(() -> new IllegalStateException("差异导出记录不存在"));
            audit(principal, "UPLOAD_COMPARISON_EXPORT_CREATE", "success", null);
            return summary(record);
        } catch (RuntimeException | IOException exception) {
            deleteQuietly(temporary);
            repository.markExportFailed(exportId, safeFailure(exception));
            audit(principal, "UPLOAD_COMPARISON_EXPORT_CREATE", "failed",
                    "UPLOAD_COMPARISON_EXPORT_FAILED");
            throw new IndicatorDetailException(
                    "UPLOAD_COMPARISON_EXPORT_FAILED", "差异表生成失败，请稍后重试。",
                    HttpStatus.INTERNAL_SERVER_ERROR, exception);
        }
    }

    private void requireExportPermission(HospitalPrincipal principal) {
        if (principal.mustChangePassword()) {
            throw error("AUTH_PASSWORD_CHANGE_REQUIRED", "请先修改初始密码。", HttpStatus.FORBIDDEN);
        }
        if (!principal.permissions().contains(IndicatorDetailService.DETAIL_EXPORT_PERMISSION)) {
            throw error("AUTH_PERMISSION_DENIED", "当前账号没有指标明细导出权限。", HttpStatus.FORBIDDEN);
        }
    }

    private static String decodeFileToken(String token) {
        if (token == null || token.isBlank() || token.length() > 512) {
            throw error("UPLOAD_FILE_TOKEN_INVALID", "上传文件标识无效。", HttpStatus.BAD_REQUEST);
        }
        try {
            String normalized = token.strip();
            String padded = normalized + "=".repeat((4 - normalized.length() % 4) % 4);
            String value = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            if (value.isBlank() || value.contains("/") || value.contains("\\")) {
                throw new IllegalArgumentException("invalid file key");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw error("UPLOAD_FILE_TOKEN_INVALID", "上传文件标识无效。", HttpStatus.BAD_REQUEST);
        }
    }

    private Path resolveOwned(String relativePath) {
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw error("DETAIL_PATH_INVALID", "导出路径无效。", HttpStatus.BAD_REQUEST);
        }
        return target;
    }

    private static void rejectSymlink(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("导出目录不能包含符号链接");
            }
            current = current.getParent();
        }
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void audit(HospitalPrincipal principal, String action, String result, String reason) {
        try {
            auditRepository.insertAudit("AUD_" + compactId(), action, result,
                    principal.userId(), principal.hospitalId(), reason,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (RuntimeException ignored) {
            // 审计短暂失败不能放宽权限，也不能泄露内部错误。
        }
    }

    private static ExportSummary summary(ExportRecord value) {
        return new ExportSummary(value.exportId(), value.runId(), value.hospitalId(), value.ruleId(),
                value.fileName(), value.rowCount(), value.status(), value.createdAt(),
                value.expiresAt(), value.downloadCount());
    }

    private static String safeId(String value, String message) {
        if (value == null || !SAFE_ID.matcher(value.strip()).matches()) {
            throw error("DETAIL_ID_INVALID", message, HttpStatus.BAD_REQUEST);
        }
        return value.strip();
    }

    private static String digits(String value) {
        String result = value == null ? "" : value.replaceAll("[^0-9]", "");
        return result.length() >= 8 ? result.substring(0, 8) : "unknown";
    }

    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String safeFailure(Throwable value) {
        String message = value.getMessage();
        return message == null ? value.getClass().getSimpleName()
                : message.substring(0, Math.min(500, message.length()));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static IndicatorDetailException error(String code, String message, HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }
}

package com.hospital.wikiagent.details;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.auth.HospitalAuthRepository;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.DetailContracts.DetailColumn;
import com.hospital.wikiagent.details.DetailContracts.DetailPage;
import com.hospital.wikiagent.details.DetailContracts.DetailQuery;
import com.hospital.wikiagent.details.DetailContracts.ExportSummary;
import com.hospital.wikiagent.details.DetailContracts.RunContext;
import com.hospital.wikiagent.details.DetailContracts.SnapshotPayload;
import com.hospital.wikiagent.details.DetailContracts.SnapshotSummary;
import com.hospital.wikiagent.details.IndicatorDetailRepository.ExportRecord;
import com.hospital.wikiagent.details.IndicatorDetailRepository.SnapshotRecord;
import com.hospital.wikiagent.runtime.WorkspacePaths;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class IndicatorDetailService {
    static final String DETAIL_VIEW_PERMISSION = "indicator_detail_view";
    static final String DETAIL_EXPORT_PERMISSION = "indicator_detail_export";
    private static final Set<String> GROUPS = Set.of("denominator", "numerator", "unmatched");
    private static final Set<Integer> PAGE_SIZES = Set.of(20, 50, 100);
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private final IndicatorDetailRepository repository;
    private final DetailQueryBuilder queryBuilder;
    private final SqlParameterBinder parameterBinder;
    private final ReadOnlySqlValidator sqlValidator;
    private final IndicatorBusinessQueryClient businessQuery;
    private final XlsxWorkbookWriter workbookWriter;
    private final ObjectMapper objectMapper;
    private final HospitalAuthRepository auditRepository;
    private final DetailProperties properties;
    private final Clock clock;
    private final Path root;
    private final Object[] runLocks = new Object[64];

    @Autowired
    public IndicatorDetailService(
            IndicatorDetailRepository repository,
            DetailQueryBuilder queryBuilder,
            SqlParameterBinder parameterBinder,
            ReadOnlySqlValidator sqlValidator,
            IndicatorBusinessQueryClient businessQuery,
            XlsxWorkbookWriter workbookWriter,
            ObjectMapper objectMapper,
            HospitalAuthRepository auditRepository,
            DetailProperties properties) {
        this(repository, queryBuilder, parameterBinder, sqlValidator, businessQuery,
                workbookWriter, objectMapper, auditRepository, properties, Clock.systemUTC());
    }

    IndicatorDetailService(
            IndicatorDetailRepository repository,
            DetailQueryBuilder queryBuilder,
            SqlParameterBinder parameterBinder,
            ReadOnlySqlValidator sqlValidator,
            IndicatorBusinessQueryClient businessQuery,
            XlsxWorkbookWriter workbookWriter,
            ObjectMapper objectMapper,
            HospitalAuthRepository auditRepository,
            DetailProperties properties,
            Clock clock) {
        this.repository = repository;
        this.queryBuilder = queryBuilder;
        this.parameterBinder = parameterBinder;
        this.sqlValidator = sqlValidator;
        this.businessQuery = businessQuery;
        this.workbookWriter = workbookWriter;
        this.objectMapper = objectMapper;
        this.auditRepository = auditRepository;
        this.properties = properties;
        this.clock = clock;
        this.root = WorkspacePaths.resolve(properties.getExportRoot());
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化指标明细目录", exception);
        }
        for (int index = 0; index < runLocks.length; index++) {
            runLocks[index] = new Object();
        }
    }

    public SnapshotSummary ensureSnapshot(HospitalPrincipal principal, String runId) {
        requirePermission(principal, DETAIL_VIEW_PERMISSION);
        return ensureSnapshotInternal(principal, requiredId(runId, "试运行编号无效"));
    }

    private SnapshotSummary ensureSnapshotInternal(HospitalPrincipal principal, String runId) {
        RunContext context = requireRun(principal, runId);
        Object lock = runLocks[Math.floorMod(runId.hashCode(), runLocks.length)];
        synchronized (lock) {
            SnapshotRecord existing = repository.snapshotByRun(runId).orElse(null);
            if (existing != null && "ready".equals(existing.status())) {
                requireSnapshotScope(existing, principal);
                validateSnapshotFile(existing);
                SnapshotSummary summary = summary(context, existing, true);
                audit(principal, "DETAIL_PREVIEW", "success", null);
                return summary;
            }
            return createSnapshot(principal, context);
        }
    }

    private SnapshotSummary createSnapshot(HospitalPrincipal principal, RunContext context) {
        if (context.aggregateNumerator() == null || context.aggregateDenominator() == null) {
            throw error("DETAIL_CONTEXT_INVALID", "本次试运行没有可核对的分子分母，请重新试运行。",
                    HttpStatus.CONFLICT);
        }
        Instant now = clock.instant();
        String snapshotId = id("SNAP_");
        String relativePath = safeSegment(context.hospitalId()) + "/"
                + safeSegment(context.runId()) + "/" + snapshotId + ".jsonl.gz";
        repository.beginSnapshot(snapshotId, context, relativePath, principal.userId(),
                now, now.plusSeconds(Math.max(1, properties.getExpireHours()) * 3600L));
        Path finalPath = resolveOwned(relativePath);
        Path temporary = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        try {
            DetailQuery query = queryBuilder.build(context, properties.getMaxRows() + 1);
            var validation = sqlValidator.validate(query.sql(), context.mainTable());
            if (!validation.ok()) {
                throw new IllegalArgumentException("明细 SQL 安全校验未通过：" + validation.message());
            }
            String executableSql = parameterBinder.bind(query.sql(), query.parameters());
            List<Map<String, Object>> rows = businessQuery.execute(executableSql).stream()
                    .map(IndicatorDetailService::normalizeRow)
                    .toList();
            if (rows.size() > properties.getMaxRows()) {
                throw error("DETAIL_ROW_LIMIT_EXCEEDED",
                        "明细超过" + String.format(Locale.ROOT, "%,d", properties.getMaxRows())
                                + "条，请缩小统计区间后重新试运行。",
                        HttpStatus.CONFLICT);
            }
            int numerator = (int) rows.stream().filter(IndicatorDetailService::meets).count();
            int denominator = rows.size();
            if (numerator != context.aggregateNumerator().intValue()
                    || denominator != context.aggregateDenominator().intValue()) {
                throw error("DETAIL_COUNT_MISMATCH", "业务数据已经变化，请重新试运行后查看明细。",
                        HttpStatus.CONFLICT);
            }
            Files.createDirectories(finalPath.getParent());
            rejectSymlinkPath(finalPath.getParent());
            writeSnapshot(temporary, context, query.columns(), rows, now);
            moveAtomically(temporary, finalPath);
            repository.markSnapshotReady(context.runId(), sha256(finalPath), denominator, numerator,
                    query.columns());
            SnapshotRecord ready = repository.snapshotByRun(context.runId())
                    .orElseThrow(() -> new IllegalStateException("明细快照状态保存失败"));
            audit(principal, "DETAIL_PREVIEW", "success", null);
            return summary(context, ready, false);
        } catch (IndicatorDetailException exception) {
            deleteQuietly(temporary);
            repository.markSnapshotFailed(context.runId(), exception.getMessage());
            audit(principal, "DETAIL_PREVIEW", "failed", exception.code());
            throw exception;
        } catch (IllegalArgumentException exception) {
            deleteQuietly(temporary);
            repository.markSnapshotFailed(context.runId(), safeFailure(exception));
            audit(principal, "DETAIL_PREVIEW", "failed", "DETAIL_CONTEXT_INVALID");
            throw new IndicatorDetailException(
                    "DETAIL_CONTEXT_INVALID",
                    "本次试运行的明细上下文不完整，请重新试运行后再查看明细。",
                    HttpStatus.CONFLICT,
                    exception);
        } catch (RuntimeException | IOException exception) {
            deleteQuietly(temporary);
            repository.markSnapshotFailed(context.runId(), safeFailure(exception));
            audit(principal, "DETAIL_PREVIEW", "failed", "DETAIL_SNAPSHOT_FAILED");
            throw new IndicatorDetailException(
                    "DETAIL_SNAPSHOT_FAILED",
                    "明细生成失败，请确认 DBHub 可用后重试。",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    exception);
        }
    }

    public DetailPage getPage(
            HospitalPrincipal principal,
            String runId,
            String group,
            int page,
            int pageSize) {
        requirePermission(principal, DETAIL_VIEW_PERMISSION);
        String normalizedGroup = group == null ? "" : group.strip().toLowerCase(Locale.ROOT);
        if (!GROUPS.contains(normalizedGroup)) {
            throw error("DETAIL_GROUP_INVALID", "明细分组无效。", HttpStatus.BAD_REQUEST);
        }
        if (page < 1 || !PAGE_SIZES.contains(pageSize)) {
            throw error("DETAIL_PAGE_INVALID", "每页条数只能选择20、50或100。",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        RunContext context = requireRun(principal, requiredId(runId, "试运行编号无效"));
        SnapshotRecord snapshot = repository.snapshotByRun(context.runId())
                .orElseThrow(() -> error("DETAIL_NOT_FOUND", "明细快照不存在，请先生成明细。",
                        HttpStatus.NOT_FOUND));
        requireSnapshotScope(snapshot, principal);
        List<Map<String, Object>> rows = readRows(snapshot);
        List<Map<String, Object>> selected = switch (normalizedGroup) {
            case "numerator" -> rows.stream().filter(IndicatorDetailService::meets).toList();
            case "unmatched" -> rows.stream().filter(row -> !meets(row)).toList();
            default -> rows;
        };
        int start = Math.min(selected.size(), (page - 1) * pageSize);
        int end = Math.min(selected.size(), start + pageSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : selected.subList(start, end)) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (DetailColumn column : snapshot.columns()) {
                item.put(column.label(), mask(row.get(column.field()), column.sensitivity()));
            }
            item.put("是否达到要求", meets(row) ? "是" : "否");
            items.add(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(item)));
        }
        audit(principal, "DETAIL_PREVIEW", "success", null);
        return new DetailPage(snapshot.snapshotId(), context.runId(), normalizedGroup,
                page, pageSize, selected.size(), items);
    }

    public ExportSummary createExport(HospitalPrincipal principal, String runId, boolean confirmed) {
        requirePermission(principal, DETAIL_EXPORT_PERMISSION);
        if (!confirmed) {
            audit(principal, "ACCESS_DENIED", "denied", "DETAIL_EXPORT_CONFIRM_REQUIRED");
            throw error("DETAIL_EXPORT_CONFIRM_REQUIRED", "导出前必须确认患者明细使用范围。",
                    HttpStatus.BAD_REQUEST);
        }
        RunContext context = requireRun(principal, requiredId(runId, "试运行编号无效"));
        SnapshotSummary summary = ensureSnapshotInternal(principal, context.runId());
        SnapshotRecord snapshot = repository.snapshotByRun(context.runId())
                .orElseThrow(() -> error("DETAIL_NOT_FOUND", "明细快照不存在。", HttpStatus.NOT_FOUND));
        List<Map<String, Object>> rows = readRows(snapshot);
        String exportId = id("EXP_");
        String start = digits(summary.statStart());
        String end = digits(summary.statEnd());
        String fileName = summary.ruleId() + "_" + start + "_" + end + "_" + exportId + ".xlsx";
        String relativePath = safeSegment(principal.hospitalId()) + "/"
                + safeSegment(context.runId()) + "/" + fileName;
        Instant now = clock.instant();
        repository.createExport(exportId, snapshot, relativePath, fileName,
                summary.denominatorCount(), principal.userId(), now,
                now.plusSeconds(Math.max(1, properties.getExpireHours()) * 3600L));
        Path finalPath = resolveOwned(relativePath);
        Path temporary = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(finalPath.getParent());
            rejectSymlinkPath(finalPath.getParent());
            workbookWriter.writeIndicatorWorkbook(temporary, new SnapshotPayload(summary, rows),
                    principal.accountId());
            moveAtomically(temporary, finalPath);
            repository.markExportReady(exportId, sha256(finalPath));
            ExportRecord ready = repository.export(exportId)
                    .orElseThrow(() -> new IllegalStateException("导出记录不存在"));
            audit(principal, "DETAIL_EXPORT_CREATE", "success", null);
            return exportSummary(ready);
        } catch (RuntimeException | IOException exception) {
            deleteQuietly(temporary);
            repository.markExportFailed(exportId, safeFailure(exception));
            audit(principal, "DETAIL_EXPORT_CREATE", "failed", "DETAIL_EXPORT_FAILED");
            throw new IndicatorDetailException(
                    "DETAIL_EXPORT_FAILED",
                    "导出文件生成失败，请稍后重试。",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    exception);
        }
    }

    public List<ExportSummary> listExports(HospitalPrincipal principal) {
        requirePermission(principal, DETAIL_EXPORT_PERMISSION);
        return repository.exports(principal.hospitalId()).stream()
                .map(IndicatorDetailService::exportSummary)
                .toList();
    }

    public DownloadFile resolveDownload(HospitalPrincipal principal, String exportId) {
        requirePermission(principal, DETAIL_EXPORT_PERMISSION);
        ExportRecord record = repository.export(requiredId(exportId, "导出编号无效"))
                .orElseThrow(() -> error("DETAIL_EXPORT_NOT_FOUND", "导出文件不存在。",
                        HttpStatus.NOT_FOUND));
        if (!principal.canAccessHospital(record.hospitalId())) {
            audit(principal, "ACCESS_DENIED", "denied", "DETAIL_EXPORT_SCOPE_DENIED");
            throw error("DETAIL_EXPORT_NOT_FOUND", "导出文件不存在。", HttpStatus.NOT_FOUND);
        }
        if (!"ready".equals(record.status())) {
            throw error("DETAIL_EXPORT_NOT_READY", "导出文件尚未生成完成。", HttpStatus.CONFLICT);
        }
        if (!record.expiresAt().isAfter(clock.instant())) {
            throw error("DETAIL_FILE_EXPIRED", "导出文件已过期，请重新生成。", HttpStatus.GONE);
        }
        Path path = resolveOwned(record.relativePath());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !sha256(path).equals(record.sha256())) {
            throw error("DETAIL_FILE_INVALID", "导出文件校验失败，请重新生成。",
                    HttpStatus.CONFLICT);
        }
        repository.recordDownload(record.exportId(), clock.instant());
        audit(principal, "DETAIL_EXPORT_DOWNLOAD", "success", null);
        return new DownloadFile(path, record.fileName());
    }

    public int defaultPageSize() {
        return PAGE_SIZES.contains(properties.getDefaultPageSize())
                ? properties.getDefaultPageSize() : 50;
    }

    private RunContext requireRun(HospitalPrincipal principal, String runId) {
        RunContext context = repository.loadRun(runId, principal.hospitalId())
                .orElseThrow(() -> error("DETAIL_RUN_NOT_FOUND", "试运行记录不存在。",
                        HttpStatus.NOT_FOUND));
        if (!principal.canAccessHospital(context.hospitalId())) {
            audit(principal, "ACCESS_DENIED", "denied", "DETAIL_SCOPE_DENIED");
            throw error("DETAIL_RUN_NOT_FOUND", "试运行记录不存在。", HttpStatus.NOT_FOUND);
        }
        return context;
    }

    private void requireSnapshotScope(SnapshotRecord snapshot, HospitalPrincipal principal) {
        if (!principal.canAccessHospital(snapshot.hospitalId())) {
            audit(principal, "ACCESS_DENIED", "denied", "DETAIL_SCOPE_DENIED");
            throw error("DETAIL_NOT_FOUND", "明细快照不存在。", HttpStatus.NOT_FOUND);
        }
    }

    private void requirePermission(HospitalPrincipal principal, String permission) {
        if (principal.mustChangePassword()) {
            audit(principal, "ACCESS_DENIED", "denied", "AUTH_PASSWORD_CHANGE_REQUIRED");
            throw error("AUTH_PASSWORD_CHANGE_REQUIRED", "请先修改初始密码再查看指标明细。",
                    HttpStatus.FORBIDDEN);
        }
        if (!principal.permissions().contains(permission)) {
            audit(principal, "ACCESS_DENIED", "denied", "AUTH_PERMISSION_DENIED");
            throw error("AUTH_PERMISSION_DENIED", "当前账号没有指标明细访问权限，请联系管理员。",
                    HttpStatus.FORBIDDEN);
        }
    }

    private SnapshotSummary summary(RunContext context, SnapshotRecord snapshot, boolean reused) {
        return new SnapshotSummary(
                snapshot.snapshotId(), context.runId(), context.hospitalId(), context.ruleId(),
                context.ruleName().isBlank() ? context.ruleId() : context.ruleName(),
                context.effectiveLevel(), context.nationalVersion(), context.hospitalVersion(),
                context.statStart(), context.statEnd(), value(snapshot.denominatorCount()),
                value(snapshot.numeratorCount()), value(snapshot.unmatchedCount()), snapshot.columns(),
                snapshot.createdAt(), snapshot.expiresAt(), reused, context.dbSource(),
                sourceTables(context));
    }

    private static List<String> sourceTables(RunContext context) {
        String schema = String.valueOf(context.fieldMapping().getOrDefault("schema", "WINDBA"));
        return switch (context.queryProfile()) {
            case "urgent_consult_sqlserver" -> List.of(
                    schema + ".INPATIENT_CONSULT_APPLY", schema + ".INP_CONSULT_INVITATION");
            case "inpatient_transfer_48h_sqlserver" -> List.of(
                    schema + ".INPATIENT_ENCOUNTER", schema + ".INPAT_TRANSFER");
            default -> context.mainTable().isBlank() ? List.of() : List.of(context.mainTable());
        };
    }

    private void writeSnapshot(
            Path path,
            RunContext context,
            List<DetailColumn> columns,
            List<Map<String, Object>> rows,
            Instant createdAt) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("run_id", context.runId());
        metadata.put("hospital_id", context.hospitalId());
        metadata.put("rule_id", context.ruleId());
        metadata.put("rule_name", context.ruleName());
        metadata.put("stat_start", context.statStart());
        metadata.put("stat_end", context.statEnd());
        metadata.put("created_at", createdAt.toString());
        metadata.put("denominator_count", rows.size());
        metadata.put("numerator_count", rows.stream().filter(IndicatorDetailService::meets).count());
        metadata.put("unmatched_count", rows.stream().filter(row -> !meets(row)).count());
        metadata.put("columns", columns);
        try (var output = new GZIPOutputStream(Files.newOutputStream(path));
                var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.write(objectMapper.writeValueAsString(Map.of("__meta__", metadata)));
            writer.newLine();
            for (Map<String, Object> row : rows) {
                writer.write(objectMapper.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    private List<Map<String, Object>> readRows(SnapshotRecord snapshot) {
        Path path = validateSnapshotFile(snapshot);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (InputStream input = new GZIPInputStream(Files.newInputStream(path));
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> value = objectMapper.readValue(line,
                        new TypeReference<LinkedHashMap<String, Object>>() {});
                if (index++ == 0 && value.containsKey("__meta__")) {
                    continue;
                }
                rows.add(normalizeRow(value));
                if (rows.size() > properties.getMaxRows()) {
                    throw error("DETAIL_FILE_INVALID", "明细文件超过安全行数限制。",
                            HttpStatus.CONFLICT);
                }
            }
        } catch (IndicatorDetailException exception) {
            throw exception;
        } catch (Exception exception) {
            throw error("DETAIL_FILE_INVALID", "明细文件无法读取，请重新生成。",
                    HttpStatus.CONFLICT);
        }
        int numerator = (int) rows.stream().filter(IndicatorDetailService::meets).count();
        if (rows.size() != value(snapshot.denominatorCount())
                || numerator != value(snapshot.numeratorCount())) {
            throw error("DETAIL_FILE_INVALID", "明细文件数量校验失败，请重新生成。",
                    HttpStatus.CONFLICT);
        }
        return List.copyOf(rows);
    }

    private Path validateSnapshotFile(SnapshotRecord snapshot) {
        if (!"ready".equals(snapshot.status())) {
            throw error("DETAIL_NOT_READY", "明细尚未生成，请重新打开详情。",
                    HttpStatus.CONFLICT);
        }
        if (!snapshot.expiresAt().isAfter(clock.instant())) {
            throw error("DETAIL_FILE_EXPIRED", "明细已过期，请重新生成。", HttpStatus.GONE);
        }
        Path path = resolveOwned(snapshot.relativePath());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !sha256(path).equals(snapshot.sha256())) {
            throw error("DETAIL_FILE_INVALID", "明细文件校验失败，请重新生成。",
                    HttpStatus.CONFLICT);
        }
        return path;
    }

    private Path resolveOwned(String relativePath) {
        Path candidate = root.resolve(relativePath == null ? "" : relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw error("DETAIL_PATH_INVALID", "文件路径无效。", HttpStatus.BAD_REQUEST);
        }
        rejectSymlinkPath(candidate.getParent());
        return candidate;
    }

    private void rejectSymlinkPath(Path path) {
        if (path == null) {
            return;
        }
        Path current = root;
        Path relative;
        try {
            relative = root.relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException exception) {
            throw error("DETAIL_PATH_INVALID", "文件路径无效。", HttpStatus.BAD_REQUEST);
        }
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw error("DETAIL_PATH_INVALID", "文件路径无效。", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, raw) -> normalized.put(
                String.valueOf(key).toLowerCase(Locale.ROOT), serializableValue(raw)));
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private static Object serializableValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString().replace('T', ' ');
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value;
    }

    private static Object mask(Object value, String sensitivity) {
        if (value == null || sensitivity == null || "none".equals(sensitivity)) {
            return value;
        }
        String text = String.valueOf(value);
        if ("name".equals(sensitivity)) {
            return text.isEmpty() ? "" : text.substring(0, 1) + "*".repeat(Math.max(1, text.length() - 1));
        }
        if ("phone".equals(sensitivity) || "id_card".equals(sensitivity)) {
            int visible = Math.min(4, text.length());
            return "*".repeat(Math.max(0, text.length() - visible))
                    + text.substring(text.length() - visible);
        }
        if (text.length() <= 4) {
            return "*".repeat(text.length());
        }
        return text.substring(0, 2) + "*".repeat(Math.max(3, text.length() - 4))
                + text.substring(text.length() - 2);
    }

    private static boolean meets(Map<String, Object> row) {
        Object value = row.get("__meets_numerator");
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static ExportSummary exportSummary(ExportRecord value) {
        return new ExportSummary(value.exportId(), value.runId(), value.hospitalId(), value.ruleId(),
                value.fileName(), value.rowCount(), value.status(), value.createdAt(),
                value.expiresAt(), value.downloadCount());
    }

    private void audit(
            HospitalPrincipal principal,
            String action,
            String result,
            String reason) {
        try {
            auditRepository.insertAudit(id("AUD_"), action, result,
                    principal.userId(), principal.hospitalId(), reason,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (RuntimeException ignored) {
            // 审计库短暂失败不能改变明细权限判断或暴露内部信息。
        }
    }

    private static String requiredId(String value, String message) {
        if (value == null || !SAFE_SEGMENT.matcher(value.strip()).matches()) {
            throw error("DETAIL_ID_INVALID", message, HttpStatus.BAD_REQUEST);
        }
        return value.strip();
    }

    private static String safeSegment(String value) {
        if (value == null || !SAFE_SEGMENT.matcher(value).matches()) {
            throw error("DETAIL_PATH_INVALID", "文件路径无效。", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private static String digits(String value) {
        String result = value == null ? "" : value.replaceAll("[^0-9]", "");
        return result.length() >= 8 ? result.substring(0, 8) : "unknown";
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("文件摘要计算失败", exception);
        }
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static String safeFailure(Exception exception) {
        return "DETAIL_INTERNAL_FAILURE:" + exception.getClass().getSimpleName();
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static IndicatorDetailException error(String code, String message, HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }

    public record DownloadFile(Path path, String fileName) {
    }
}

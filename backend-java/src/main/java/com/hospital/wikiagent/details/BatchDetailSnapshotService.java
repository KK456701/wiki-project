package com.hospital.wikiagent.details;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.DetailContracts.DetailColumn;
import com.hospital.wikiagent.details.IndicatorDetailRepository.SnapshotRecord;
import com.hospital.wikiagent.runtime.WorkspacePaths;

/**
 * 将批量指标的确定性明细物化到现有 {@code med_indicator_detail_snapshot} 基础设施。
 *
 * <p>首次创建由调用方在医院级抽取锁内完成查询和卡片对账；快照就绪后，分子、分母和
 * 翻页只顺序读取同一份 gzip JSONL，不再访问全局可变真实库中间表。批次任务保存
 * snapshotId，详情运行 ID 同时包含 batchRunId、任务位置、SQL 哈希和契约版本摘要，
 * 防止跨批次、跨口径或跨契约复用。</p>
 */
@Service
public class BatchDetailSnapshotService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(BatchDetailSnapshotService.class);
    private static final int[] PAGE_SIZES = {20, 50, 100, 200};

    private final IndicatorDetailRepository repository;
    private final BatchJobStore batchJobs;
    private final ObjectMapper objectMapper;
    private final DetailProperties properties;
    private final Clock clock;
    private final Path root;
    private final Object[] locks = new Object[64];

    @Autowired
    public BatchDetailSnapshotService(
            IndicatorDetailRepository repository,
            BatchJobStore batchJobs,
            ObjectMapper objectMapper,
            DetailProperties properties) {
        this(repository, batchJobs, objectMapper, properties, Clock.systemUTC());
    }

    BatchDetailSnapshotService(
            IndicatorDetailRepository repository,
            BatchJobStore batchJobs,
            ObjectMapper objectMapper,
            DetailProperties properties,
            Clock clock) {
        this.repository = repository;
        this.batchJobs = batchJobs;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.root = WorkspacePaths.resolve(properties.getExportRoot());
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化批量指标明细目录", exception);
        }
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
    }

    /**
     * 返回已有快照页；快照不存在时只允许一个调用方执行 materializer。
     */
    public BatchDetailPage loadOrCreate(
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            String group,
            int page,
            int pageSize,
            Supplier<MaterializedDetail> materializer) {
        validatePage(group, page, pageSize);
        String detailRunId = detailRunId(task);
        Object lock = locks[Math.floorMod(detailRunId.hashCode(), locks.length)];
        synchronized (lock) {
            SnapshotRecord existing = repository.snapshotByRun(detailRunId).orElse(null);
            if (existing != null) {
                verifyBinding(principal, task, existing);
                if ("ready".equals(existing.status())) {
                    return page(task, existing, group, page, pageSize, true);
                }
                if (!existing.expiresAt().isAfter(clock.instant())) {
                    throw error("DETAIL_FILE_EXPIRED",
                            "该批次明细快照已过期，请重新执行全量计算。", HttpStatus.GONE);
                }
            } else if (task.detailSnapshotId() != null) {
                throw error("DETAIL_FILE_INVALID",
                        "批次记录关联的明细快照不存在，请重新执行全量计算。",
                        HttpStatus.CONFLICT);
            }
            MaterializedDetail materialized = materializer.get();
            SnapshotRecord ready = create(principal, task, detailRunId, materialized);
            return page(task, ready, group, page, pageSize, false);
        }
    }

    private SnapshotRecord create(
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            String detailRunId,
            MaterializedDetail materialized) {
        long snapshotStarted = System.nanoTime();
        if (materialized.denominatorCount() != task.denominatorCount()
                || materialized.numeratorCount() != task.numeratorCount()) {
            throw error("DETAIL_COUNT_MISMATCH",
                    "明细与原批次卡片数量不一致，已拒绝生成快照。",
                    HttpStatus.CONFLICT);
        }
        if (materialized.rows().size() != materialized.denominatorCount()) {
            throw error("DETAIL_COUNT_MISMATCH",
                    "明细母集行数与分母不一致，已拒绝生成快照。",
                    HttpStatus.CONFLICT);
        }
        if (materialized.rows().size() > properties.getMaxRows()) {
            throw error("DETAIL_ROW_LIMIT_EXCEEDED",
                    "明细超过安全行数上限，请缩小统计区间。",
                    HttpStatus.CONFLICT);
        }
        long actualNumerator = materialized.rows().stream()
                .filter(BatchDetailSnapshotService::meets).count();
        if (actualNumerator != materialized.numeratorCount()) {
            throw error("DETAIL_COUNT_MISMATCH",
                    "明细判定列与分子数量不一致，已拒绝生成快照。",
                    HttpStatus.CONFLICT);
        }

        Instant now = clock.instant();
        String snapshotId = "SNAP_" + compactUuid();
        String relativePath = safe(principal.hospitalId()) + "/batch-details/"
                + safe(task.batchRunId()) + "/" + snapshotId + ".jsonl.gz";
        repository.beginSnapshot(
                snapshotId,
                detailRunId,
                principal.hospitalId(),
                task.ruleId(),
                relativePath,
                principal.userId(),
                now,
                now.plusSeconds(Math.max(1, properties.getExpireHours()) * 3600L));
        Path finalPath = resolveOwned(relativePath);
        Path temporary = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(finalPath.getParent());
            long fileStarted = System.nanoTime();
            writeSnapshot(temporary, task, materialized, now);
            moveAtomically(temporary, finalPath);
            long fileDurationMs = elapsedMs(fileStarted);
            List<DetailColumn> columns = columns(materialized.rows());
            long indexStarted = System.nanoTime();
            repository.replaceSnapshotRows(
                    snapshotId,
                    materialized.rows(),
                    MrasDetailSqlExtractor.NUMERATOR_FLAG_COLUMN);
            long indexDurationMs = elapsedMs(indexStarted);
            long finalizeStarted = System.nanoTime();
            repository.markSnapshotReady(
                    detailRunId,
                    sha256(finalPath),
                    materialized.denominatorCount(),
                    materialized.numeratorCount(),
                    columns);
            batchJobs.bindDetailSnapshot(task.batchRunId(), task.position(), snapshotId);
            long finalizeDurationMs = elapsedMs(finalizeStarted);
            LOGGER.info(
                    "批次明细性能 {}（profileId={}）：抽取={}ms，SQL={}ms，文件={}ms，分页索引={}ms，收尾={}ms，快照总计={}ms",
                    task.ruleId(),
                    task.profileId(),
                    materialized.extractionDurationMs(),
                    materialized.queryDurationMs(),
                    fileDurationMs,
                    indexDurationMs,
                    finalizeDurationMs,
                    elapsedMs(snapshotStarted));
            return repository.snapshotByRun(detailRunId)
                    .orElseThrow(() -> new IllegalStateException("批量明细快照状态保存失败"));
        } catch (IndicatorDetailException exception) {
            deleteQuietly(temporary);
            repository.markSnapshotFailed(detailRunId, exception.getMessage());
            throw exception;
        } catch (RuntimeException | IOException exception) {
            deleteQuietly(temporary);
            repository.markSnapshotFailed(detailRunId, safeFailure(exception));
            throw new IndicatorDetailException(
                    "DETAIL_SNAPSHOT_FAILED",
                    "明细快照生成失败，请稍后重试。",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    exception);
        }
    }

    private BatchDetailPage page(
            BatchTaskSnapshot task,
            SnapshotRecord snapshot,
            String group,
            int page,
            int pageSize,
            boolean reused) {
        Path path = validateSnapshotFile(snapshot);
        int total = "numerator".equals(group)
                ? value(snapshot.numeratorCount()) : value(snapshot.denominatorCount());
        int start = (page - 1) * pageSize;
        boolean numeratorOnly = "numerator".equals(group);
        int indexedTotal = repository.snapshotRowCount(snapshot.snapshotId(), numeratorOnly);
        if (indexedTotal != total) {
            throw error("DETAIL_FILE_INVALID",
                    "明细快照分页索引数量校验失败，请重新执行全量计算。",
                    HttpStatus.CONFLICT);
        }
        List<Map<String, Object>> items = repository.pageSnapshotRows(
                snapshot.snapshotId(), numeratorOnly, start, pageSize);
        return new BatchDetailPage(
                snapshot.snapshotId(),
                task.batchRunId(),
                task.ruleId(),
                task.profileId(),
                group,
                page,
                pageSize,
                total,
                value(snapshot.numeratorCount()),
                value(snapshot.denominatorCount()),
                reused,
                items);
    }

    private void writeSnapshot(
            Path path,
            BatchTaskSnapshot task,
            MaterializedDetail materialized,
            Instant createdAt) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("batchRunId", task.batchRunId());
        metadata.put("ruleId", task.ruleId());
        metadata.put("profileId", task.profileId());
        metadata.put("detailKind", task.detailKind());
        metadata.put("overviewSqlHash", task.overviewSqlHash());
        metadata.put("contractVersion", task.detailContractVersion());
        metadata.put("statStart", task.statStart());
        metadata.put("statEnd", task.statEnd());
        metadata.put("createdAt", createdAt.toString());
        metadata.put("denominatorCount", materialized.denominatorCount());
        metadata.put("numeratorCount", materialized.numeratorCount());
        try (var output = new GZIPOutputStream(Files.newOutputStream(path));
                var writer = new BufferedWriter(
                        new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.write(objectMapper.writeValueAsString(Map.of("__meta__", metadata)));
            writer.newLine();
            for (Map<String, Object> row : materialized.rows()) {
                writer.write(objectMapper.writeValueAsString(row));
                writer.newLine();
            }
        }
    }

    private void verifyBinding(
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            SnapshotRecord snapshot) {
        if (!principal.canAccessHospital(snapshot.hospitalId())
                || !task.ruleId().equals(snapshot.ruleId())
                || !principal.userId().equals(snapshot.createdBy())) {
            throw error("DETAIL_NOT_FOUND", "明细快照不存在。", HttpStatus.NOT_FOUND);
        }
        if (task.detailSnapshotId() != null
                && !task.detailSnapshotId().equals(snapshot.snapshotId())) {
            throw error("DETAIL_FILE_INVALID",
                    "批次与明细快照绑定不一致，请重新执行全量计算。",
                    HttpStatus.CONFLICT);
        }
        if (value(snapshot.numeratorCount()) != task.numeratorCount()
                || value(snapshot.denominatorCount()) != task.denominatorCount()) {
            throw error("DETAIL_COUNT_MISMATCH",
                    "明细快照与原批次卡片不一致，已拒绝返回。",
                    HttpStatus.CONFLICT);
        }
    }

    private Path validateSnapshotFile(SnapshotRecord snapshot) {
        if (!"ready".equals(snapshot.status())) {
            throw error("DETAIL_NOT_READY", "明细快照尚未就绪。", HttpStatus.CONFLICT);
        }
        if (!snapshot.expiresAt().isAfter(clock.instant())) {
            throw error("DETAIL_FILE_EXPIRED",
                    "该批次明细快照已过期，请重新执行全量计算。", HttpStatus.GONE);
        }
        Path path = resolveOwned(snapshot.relativePath());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !sha256(path).equals(snapshot.sha256())) {
            throw error("DETAIL_FILE_INVALID",
                    "明细快照文件校验失败，请重新执行全量计算。",
                    HttpStatus.CONFLICT);
        }
        return path;
    }

    private Path resolveOwned(String relativePath) {
        Path candidate = root.resolve(relativePath == null ? "" : relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw error("DETAIL_PATH_INVALID", "明细文件路径无效。", HttpStatus.BAD_REQUEST);
        }
        return candidate;
    }

    private static List<DetailColumn> columns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return rows.get(0).keySet().stream()
                .map(field -> new DetailColumn(
                        field,
                        MrasDetailSqlExtractor.NUMERATOR_FLAG_COLUMN.equalsIgnoreCase(field)
                                ? "是否命中分子" : field,
                        "none"))
                .toList();
    }

    private static String detailRunId(BatchTaskSnapshot task) {
        String material = task.batchRunId() + "|" + task.position() + "|" + task.ruleId()
                + "|" + String.valueOf(task.profileId()) + "|" + task.overviewSqlHash()
                + "|" + task.detailContractVersion();
        return "BDET_" + hash(material).substring(0, 48);
    }

    private static boolean meets(Map<String, Object> row) {
        Object flag = row.get(MrasDetailSqlExtractor.NUMERATOR_FLAG_COLUMN);
        if (flag instanceof Number number) {
            return number.intValue() == 1;
        }
        return flag != null && ("1".equals(flag.toString().strip())
                || "true".equalsIgnoreCase(flag.toString().strip()));
    }

    private static void validatePage(String group, int page, int pageSize) {
        if (!"numerator".equals(group) && !"denominator".equals(group)) {
            throw error("DETAIL_GROUP_INVALID",
                    "group 只能是 numerator 或 denominator", HttpStatus.BAD_REQUEST);
        }
        boolean allowed = false;
        for (int size : PAGE_SIZES) {
            allowed = allowed || size == pageSize;
        }
        if (page < 1 || !allowed) {
            throw error("DETAIL_PAGE_INVALID",
                    "page 必须大于 0，pageSize 只能是 20、50、100 或 200。",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static String safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) {
            throw error("DETAIL_PATH_INVALID", "明细文件路径无效。", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件由后续运维清理，不掩盖原始异常。
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("无法计算明细文件摘要", exception);
        }
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static String safeFailure(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 1_000 ? message.substring(0, 1_000) : message;
    }

    private static IndicatorDetailException error(
            String code, String message, HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }

    public record MaterializedDetail(
            List<Map<String, Object>> rows,
            int numeratorCount,
            int denominatorCount,
            long extractionDurationMs,
            long queryDurationMs) {
        public MaterializedDetail {
            rows = List.copyOf(rows);
        }
    }

    public record BatchDetailPage(
            String snapshotId,
            String batchRunId,
            String ruleId,
            String profileId,
            String group,
            int page,
            int pageSize,
            int total,
            int numeratorCount,
            int denominatorCount,
            boolean snapshotReused,
            List<Map<String, Object>> rows) {
        public BatchDetailPage {
            rows = List.copyOf(rows);
        }
    }
}

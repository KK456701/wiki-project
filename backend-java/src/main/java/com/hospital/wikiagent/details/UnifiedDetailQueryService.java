package com.hospital.wikiagent.details;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.agent.mras.MrasDetailContractRegistry;
import com.hospital.wikiagent.agent.mras.MrasDetailKind;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor.DetailExtraction;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.BatchDetailSnapshotService.BatchDetailPage;
import com.hospital.wikiagent.details.BatchDetailSnapshotService.MaterializedDetail;

/** 为主聊天与异常排查提供同一批次、同一快照的指标明细查询。 */
@Service
public class UnifiedDetailQueryService {
    private final MrasSqlExecutionService execution;
    private final MrasDetailSqlExtractor extractor;
    private final BatchJobStore batchJobs;
    private final BatchDetailSnapshotService snapshots;
    private final MrasSpecialDetailService specialDetails;
    private final MrasSpecialDetailSnapshotService specialSnapshots;

    public UnifiedDetailQueryService(
            MrasSqlExecutionService execution,
            MrasDetailSqlExtractor extractor,
            BatchJobStore batchJobs,
            BatchDetailSnapshotService snapshots,
            MrasSpecialDetailService specialDetails,
            MrasSpecialDetailSnapshotService specialSnapshots) {
        this.execution = execution;
        this.extractor = extractor;
        this.batchJobs = batchJobs;
        this.snapshots = snapshots;
        this.specialDetails = specialDetails;
        this.specialSnapshots = specialSnapshots;
    }

    public Map<String, Object> load(
            HospitalPrincipal principal,
            String batchRunId,
            String ruleId,
            String profileId,
            String group,
            int page,
            int pageSize) {
        if (!execution.supports(ruleId)) {
            throw error("DETAIL_INDICATOR_UNSUPPORTED",
                    "指标 " + ruleId + " 暂不支持明细查询", HttpStatus.NOT_FOUND);
        }
        BatchTaskSnapshot task = batchJobs.loadTask(
                        batchRunId, principal.hospitalId(), principal.userId(), ruleId, profileId)
                .orElseThrow(() -> error(
                        "DETAIL_RUN_NOT_FOUND",
                        "原批次卡片不存在或无权访问，请重新计算后查看明细。",
                        HttpStatus.NOT_FOUND));
        return loadTask(principal, task, group, page, pageSize);
    }

    public Map<String, Object> loadDiagnosis(
            HospitalPrincipal principal,
            String sessionKey,
            BatchTaskSnapshot frozenTask,
            String group,
            int page,
            int pageSize) {
        BatchTaskSnapshot task = batchJobs.ensureDiagnosisTask(
                sessionKey, principal.hospitalId(), principal.userId(), frozenTask);
        return loadTask(principal, task, group, page, pageSize);
    }

    private Map<String, Object> loadTask(
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            String group,
            int page,
            int pageSize) {
        if (!"SUCCESS".equals(task.status()) && !"NO_SAMPLE".equals(task.status())) {
            throw error("DETAIL_RUN_FAILED", "原批次指标未成功计算，不能查看明细。",
                    HttpStatus.CONFLICT);
        }
        LocalDateTime start = parseTime(task.statStart(), false);
        LocalDateTime end = parseTime(task.statEnd(), true);
        MrasDetailKind kind = MrasDetailContractRegistry.kindFor(
                task.ruleId(), task.profileId());
        if (task.detailKind() == null || !kind.name().equals(task.detailKind())) {
            throw error("DETAIL_CONTRACT_CHANGED",
                    "原批次的详情类型与当前契约不一致，请重新计算指标。",
                    HttpStatus.CONFLICT);
        }
        if (kind != MrasDetailKind.COUNT_RATIO) {
            return jsonSafeResponse(specialSnapshots.loadOrCreate(
                    principal, task, kind, group, page, pageSize,
                    () -> specialDetails.details(task, kind, start, end)));
        }
        return standard(principal, task, kind, group, page, pageSize, start, end);
    }

    private Map<String, Object> standard(
            HospitalPrincipal principal,
            BatchTaskSnapshot task,
            MrasDetailKind kind,
            String group,
            int page,
            int pageSize,
            LocalDateTime start,
            LocalDateTime end) {
        String normalized = group == null || group.isBlank()
                ? DetailGroupCatalog.defaultGroup(kind) : group;
        if (!DetailGroupCatalog.keys(kind).contains(normalized)) {
            throw error("DETAIL_GROUP_INVALID", "普通比例不支持明细分组 " + normalized,
                    HttpStatus.BAD_REQUEST);
        }
        if (task.numeratorCount() == null || task.denominatorCount() == null) {
            throw error("DETAIL_CONTEXT_INVALID", "原批次没有可核对的分子分母。",
                    HttpStatus.CONFLICT);
        }
        DetailExtraction extraction = extractor.extract(task.ruleId(), task.profileId());
        if (!extraction.supported()) {
            throw error("DETAIL_UNSUPPORTED",
                    extraction.detailKind().name() + "：" + extraction.unsupportedReason(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (task.overviewSqlHash() == null
                || !task.overviewSqlHash().equals(extraction.overviewSqlHash())) {
            throw error("DETAIL_CONTRACT_CHANGED",
                    "知识库口径已变化，请重新计算指标后查看明细。",
                    HttpStatus.CONFLICT);
        }
        long started = System.nanoTime();
        BatchDetailPage detail = snapshots.loadOrCreate(
                principal, task, normalized, page, pageSize,
                () -> materialize(task, extraction, start, end));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleId", task.ruleId());
        body.put("ruleName", task.ruleName());
        body.put("batchRunId", task.batchRunId());
        body.put("group", normalized);
        body.put("statStart", start.toString());
        body.put("statEnd", end.toString());
        body.put("page", detail.page());
        body.put("pageSize", detail.pageSize());
        body.put("rowCount", detail.total());
        body.put("rows", jsonSafeRows(detail.rows()));
        body.put("truncated", detail.page() * detail.pageSize() < detail.total());
        body.put("snapshotId", detail.snapshotId());
        body.put("snapshotReused", detail.snapshotReused());
        body.put("durationMs", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        body.put("sqlSource", detail.snapshotReused()
                ? "batch_detail_snapshot" : "mras_extracted");
        body.put("detailKind", extraction.detailKind().name());
        body.put("detailContractVersion", extraction.contractVersion());
        body.put("cardNumerator", task.numeratorCount());
        body.put("cardDenominator", task.denominatorCount());
        body.put("detailNumerator", detail.numeratorCount());
        body.put("detailDenominator", detail.denominatorCount());
        body.put("overviewSqlHash", extraction.overviewSqlHash());
        body.put("groups", DetailGroupCatalog.descriptors(kind, Map.of(
                "numerator", detail.numeratorCount(),
                "denominator", detail.denominatorCount(),
                "difference", detail.denominatorCount() - detail.numeratorCount())));
        body.put("summary", Map.of(
                "numeratorCount", detail.numeratorCount(),
                "denominatorCount", detail.denominatorCount(),
                "differenceCount", detail.denominatorCount() - detail.numeratorCount()));
        return Map.copyOf(body);
    }

    private static Map<String, Object> jsonSafeResponse(Map<String, Object> body) {
        Object value = body.get("rows");
        if (!(value instanceof List<?> list)) {
            return body;
        }
        List<Map<String, Object>> rows = list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(UnifiedDetailQueryService::stringKeyMap)
                .toList();
        Map<String, Object> safe = new LinkedHashMap<>(body);
        safe.put("rows", jsonSafeRows(rows));
        return Map.copyOf(safe);
    }

    static List<Map<String, Object>> jsonSafeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> safe = new LinkedHashMap<>();
            row.forEach((key, value) -> safe.put(key, jsonSafeInteger(value)));
            return Collections.unmodifiableMap(safe);
        }).toList();
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Object jsonSafeInteger(Object value) {
        BigInteger integer = null;
        if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else if (value instanceof BigDecimal decimal
                && decimal.stripTrailingZeros().scale() <= 0) {
            integer = decimal.toBigIntegerExact();
        } else if (value instanceof Long longValue) {
            integer = BigInteger.valueOf(longValue);
        }
        if (integer == null
                || integer.abs().compareTo(
                        BigInteger.valueOf(9_007_199_254_740_991L)) <= 0) {
            return value;
        }
        return integer.toString();
    }

    private MaterializedDetail materialize(
            BatchTaskSnapshot task,
            DetailExtraction extraction,
            LocalDateTime start,
            LocalDateTime end) {
        ToolResult result = execution.executeBoundDetail(
                task.batchRunId(), task.ruleId(), task.profileId(), extraction.detailSql(),
                extraction.overviewSqlHash(), start, end,
                task.numeratorCount(), task.denominatorCount());
        if (!result.ok()) {
            String code = "MRAS_DETAIL_COUNT_MISMATCH".equals(result.code())
                    ? "DETAIL_COUNT_MISMATCH" : "DETAIL_QUERY_FAILED";
            throw error(code, "明细查询失败：" + result.summary(),
                    code.equals("DETAIL_COUNT_MISMATCH")
                            ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY);
        }
        return new MaterializedDetail(
                rows(result.data().get("rows")),
                Math.toIntExact(number(result.data().get("numeratorCount"))),
                Math.toIntExact(number(result.data().get("denominatorCount"))),
                number(result.data().get("extractionDurationMs")),
                number(result.data().get("durationMs")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return value instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDateTime parseTime(String value, boolean endOfDay) {
        try {
            String normalized = value == null ? "" : value.strip().replace('T', ' ');
            if (normalized.length() <= 10) {
                LocalDate date = LocalDate.parse(normalized);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            return LocalDateTime.parse(normalized.replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            throw error("DETAIL_TIME_INVALID", "统计时间格式不正确：" + value,
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static IndicatorDetailException error(
            String code, String message, HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }
}

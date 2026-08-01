package com.hospital.wikiagent.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlExtractor.DetailExtraction;
import com.hospital.wikiagent.agent.mras.MrasDetailContractRegistry;
import com.hospital.wikiagent.agent.mras.MrasDetailKind;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.batch.BatchJobStore;
import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.details.BatchDetailSnapshotService;
import com.hospital.wikiagent.details.BatchDetailSnapshotService.BatchDetailPage;
import com.hospital.wikiagent.details.BatchDetailSnapshotService.MaterializedDetail;
import com.hospital.wikiagent.details.IndicatorDetailException;
import com.hospital.wikiagent.details.MrasSpecialDetailService;
import com.hospital.wikiagent.details.MrasSpecialDetailSnapshotService;

/**
 * 指标结果卡片的「明细」按钮接口：按 rule_id + 统计区间直接查询分子/分母明细。
 *
 * <p>明细 SQL 由 {@link MrasDetailSqlExtractor} 从「目标表-概览」口径（卡片分子/分母的真实
 * 来源）确定性提取（逐字保留 FROM/JOIN/WHERE，仅换 SELECT*、删 GROUP BY、分子追加判定），
 * 行数与卡片口径一致；无法安全机械改写的指标返回统一的 DETAIL_UNSUPPORTED 错误，
 * 不降级、不回退科室统计口径、不返回对不上的患者明细。</p>
 */
@RestController
@RequestMapping("/api/kb/rules")
public class IndicatorDetailQueryController {

    private final HospitalAuthService authService;
    private final MrasSqlExecutionService mrasExecution;
    private final MrasDetailSqlExtractor detailExtractor;
    private final BatchJobStore batchJobs;
    private final BatchDetailSnapshotService snapshots;
    private final MrasSpecialDetailService specialDetails;
    private final MrasSpecialDetailSnapshotService specialSnapshots;

    public IndicatorDetailQueryController(
            HospitalAuthService authService,
            MrasSqlExecutionService mrasExecution,
            MrasDetailSqlExtractor detailExtractor,
            BatchJobStore batchJobs,
            BatchDetailSnapshotService snapshots,
            MrasSpecialDetailService specialDetails,
            MrasSpecialDetailSnapshotService specialSnapshots) {
        this.authService = authService;
        this.mrasExecution = mrasExecution;
        this.detailExtractor = detailExtractor;
        this.batchJobs = batchJobs;
        this.snapshots = snapshots;
        this.specialDetails = specialDetails;
        this.specialSnapshots = specialSnapshots;
    }

    @GetMapping("/{ruleId}/details")
    public Map<String, Object> details(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String ruleId,
            @RequestParam(defaultValue = "numerator") String group,
            @RequestParam String batchRunId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String profileId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        HospitalPrincipal principal =
                authService.authenticate(BearerTokens.require(authorization));
        if (!mrasExecution.supports(ruleId)) {
            throw new IndicatorDetailException("DETAIL_INDICATOR_UNSUPPORTED",
                    "指标 " + ruleId + " 暂不支持明细查询", HttpStatus.NOT_FOUND);
        }
        BatchTaskSnapshot task = batchJobs.loadTask(
                        batchRunId, principal.hospitalId(), principal.userId(), ruleId, profileId)
                .orElseThrow(() -> new IndicatorDetailException(
                        "DETAIL_RUN_NOT_FOUND",
                        "原批次卡片不存在或无权访问，请重新计算后查看明细。",
                        HttpStatus.NOT_FOUND));
        if (!"SUCCESS".equals(task.status()) && !"NO_SAMPLE".equals(task.status())) {
            throw new IndicatorDetailException(
                    "DETAIL_RUN_FAILED",
                    "原批次指标未成功计算，不能查看明细。",
                    HttpStatus.CONFLICT);
        }
        LocalDateTime startTime = parseStart(task.statStart());
        LocalDateTime endTime = parseEnd(task.statEnd());
        MrasDetailKind detailKind =
                MrasDetailContractRegistry.kindFor(ruleId, task.profileId());
        if (task.detailKind() == null || !detailKind.name().equals(task.detailKind())) {
            throw new IndicatorDetailException(
                    "DETAIL_CONTRACT_CHANGED",
                    "原批次的详情类型与当前契约不一致，请重新计算指标。",
                    HttpStatus.CONFLICT);
        }
        if (detailKind != MrasDetailKind.COUNT_RATIO) {
            return specialSnapshots.loadOrCreate(
                    principal,
                    task,
                    detailKind,
                    group,
                    page,
                    pageSize,
                    () -> specialDetails.details(task, detailKind, startTime, endTime));
        }
        if (!"numerator".equals(group) && !"denominator".equals(group)) {
            throw new IndicatorDetailException("DETAIL_GROUP_INVALID",
                    "普通比例的 group 只能是 numerator 或 denominator",
                    HttpStatus.BAD_REQUEST);
        }
        if (task.numeratorCount() == null || task.denominatorCount() == null) {
            throw new IndicatorDetailException(
                    "DETAIL_CONTEXT_INVALID",
                    "原批次没有可核对的分子分母。",
                    HttpStatus.CONFLICT);
        }
        // 只从「目标表-概览」口径确定性提取单结果集明细 SQL（含 __meets_numerator 判定列）；
        // 无法安全机械改写的指标显式报错，统一走 DETAIL_UNSUPPORTED（不降级、不回退科室统计口径）。
        DetailExtraction extraction = detailExtractor.extract(ruleId, task.profileId());
        if (!extraction.supported()) {
            throw new IndicatorDetailException("DETAIL_UNSUPPORTED",
                    extraction.detailKind().name() + "："
                            + extraction.unsupportedReason(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (task.overviewSqlHash() == null
                || !task.overviewSqlHash().equals(extraction.overviewSqlHash())) {
            throw new IndicatorDetailException(
                    "DETAIL_CONTRACT_CHANGED",
                    "知识库口径已变化，请重新计算指标后查看明细。",
                    HttpStatus.CONFLICT);
        }

        long requestStarted = System.nanoTime();
        BatchDetailPage detailPage = snapshots.loadOrCreate(
                principal,
                task,
                group,
                page,
                pageSize,
                () -> materialize(
                        ruleId, task.profileId(), extraction, startTime, endTime, task));
        long totalDurationMs = Math.max(
                0L, (System.nanoTime() - requestStarted) / 1_000_000L);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleId", ruleId);
        body.put("ruleName", task.ruleName());
        body.put("batchRunId", batchRunId);
        body.put("group", group);
        body.put("statStart", startTime.toString());
        body.put("statEnd", endTime.toString());
        body.put("page", detailPage.page());
        body.put("pageSize", detailPage.pageSize());
        body.put("rowCount", detailPage.total());
        body.put("rows", detailPage.rows());
        body.put("truncated",
                detailPage.page() * detailPage.pageSize() < detailPage.total());
        body.put("snapshotId", detailPage.snapshotId());
        body.put("snapshotReused", detailPage.snapshotReused());
        body.put("durationMs", totalDurationMs);
        body.put("sqlSource", detailPage.snapshotReused()
                ? "batch_detail_snapshot" : "mras_extracted");
        body.put("detailKind", extraction.detailKind().name());
        body.put("detailContractVersion", extraction.contractVersion());
        // 运行绑定：透出卡片金标准与概览 SQL 哈希，供前端/审计核对本次明细与卡片同源。
        body.put("cardNumerator", task.numeratorCount());
        body.put("cardDenominator", task.denominatorCount());
        body.put("detailNumerator", detailPage.numeratorCount());
        body.put("detailDenominator", detailPage.denominatorCount());
        body.put("overviewSqlHash", extraction.overviewSqlHash());
        return body;
    }

    private MaterializedDetail materialize(
            String ruleId,
            String profileId,
            DetailExtraction extraction,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BatchTaskSnapshot task) {
        ToolResult result = mrasExecution.executeBoundDetail(
                ruleId,
                profileId,
                extraction.detailSql(),
                extraction.overviewSqlHash(),
                startTime,
                endTime,
                task.numeratorCount(),
                task.denominatorCount());
        if (!result.ok()) {
            if ("MRAS_DETAIL_COUNT_MISMATCH".equals(result.code())) {
                throw new IndicatorDetailException(
                        "DETAIL_COUNT_MISMATCH", result.summary(), HttpStatus.CONFLICT);
            }
            throw new IndicatorDetailException(
                    "DETAIL_QUERY_FAILED",
                    "明细查询失败：" + result.summary(),
                    HttpStatus.BAD_GATEWAY);
        }
        Map<String, Object> data = result.data();
        return new MaterializedDetail(
                asRows(data.get("rows")),
                Math.toIntExact(number(data.get("numeratorCount"))),
                Math.toIntExact(number(data.get("denominatorCount"))),
                number(data.get("extractionDurationMs")),
                number(data.get("durationMs")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static long number(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDateTime parseStart(String text) {
        return parseDateTime(text, false);
    }

    private static LocalDateTime parseEnd(String text) {
        return parseDateTime(text, true);
    }

    /** 支持 yyyy-MM-dd 或 ISO 日期时间；纯日期时结束时间取当天 23:59:59。 */
    private static LocalDateTime parseDateTime(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) {
            throw new IndicatorDetailException("DETAIL_TIME_INVALID", "start/end 不能为空",
                    HttpStatus.BAD_REQUEST);
        }
        String normalized = text.strip().replace('T', ' ');
        try {
            if (normalized.length() <= 10) {
                LocalDate date = LocalDate.parse(normalized);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            return LocalDateTime.parse(normalized.replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            throw new IndicatorDetailException("DETAIL_TIME_INVALID", "时间格式不正确：" + text,
                    HttpStatus.BAD_REQUEST);
        }
    }
}

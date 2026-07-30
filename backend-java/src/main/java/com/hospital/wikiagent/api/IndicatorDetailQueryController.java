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
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;
import com.hospital.wikiagent.details.IndicatorDetailException;

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

    /** 单次响应最多返回的明细行数，避免大结果集拖垮前端渲染。 */
    private static final int MAX_ROWS = 200;

    private final HospitalAuthService authService;
    private final MrasSqlExecutionService mrasExecution;
    private final MrasDetailSqlExtractor detailExtractor;

    public IndicatorDetailQueryController(
            HospitalAuthService authService,
            MrasSqlExecutionService mrasExecution,
            MrasDetailSqlExtractor detailExtractor) {
        this.authService = authService;
        this.mrasExecution = mrasExecution;
        this.detailExtractor = detailExtractor;
    }

    @GetMapping("/{ruleId}/details")
    public Map<String, Object> details(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String ruleId,
            @RequestParam(defaultValue = "numerator") String group,
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(required = false) String profileId) {
        authService.authenticate(BearerTokens.require(authorization));
        if (!"numerator".equals(group) && !"denominator".equals(group)) {
            throw new IndicatorDetailException("DETAIL_GROUP_INVALID",
                    "group 只能是 numerator 或 denominator", HttpStatus.BAD_REQUEST);
        }
        if (!mrasExecution.supports(ruleId)) {
            throw new IndicatorDetailException("DETAIL_INDICATOR_UNSUPPORTED",
                    "指标 " + ruleId + " 暂不支持明细查询", HttpStatus.NOT_FOUND);
        }
        LocalDateTime startTime = parseStart(start);
        LocalDateTime endTime = parseEnd(end);
        boolean denominator = "denominator".equals(group);

        // 只从「目标表-概览」口径确定性提取单结果集明细 SQL（含 __meets_numerator 判定列）；
        // 无法安全机械改写的指标显式报错，统一走 DETAIL_UNSUPPORTED（不降级、不回退科室统计口径）。
        DetailExtraction extraction = detailExtractor.extract(ruleId, profileId);
        if (!extraction.supported()) {
            throw new IndicatorDetailException("DETAIL_UNSUPPORTED",
                    "该指标口径暂不支持明细下钻：" + extraction.unsupportedReason(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // 服务端对账：跑概览拿卡片分子/分母，再跑单结果集明细，行数与卡片不一致即硬报错。
        ToolResult result = mrasExecution.executeReconciledDetail(
                ruleId, profileId, extraction.detailSql(), extraction.overviewSqlHash(),
                startTime, endTime);
        if (!result.ok()) {
            if ("MRAS_DETAIL_COUNT_MISMATCH".equals(result.code())) {
                throw new IndicatorDetailException("DETAIL_COUNT_MISMATCH",
                        result.summary(), HttpStatus.CONFLICT);
            }
            throw new IndicatorDetailException("DETAIL_QUERY_FAILED",
                    "明细查询失败：" + result.summary(), HttpStatus.BAD_GATEWAY);
        }

        Map<String, Object> data = result.data();
        List<Map<String, Object>> allRows = asRows(data.get("rows"));
        // 分子恒为分母子集：分母返回全部行，分子按判定列过滤，杜绝分子>分母类假阳性。
        List<Map<String, Object>> groupRows = denominator
                ? allRows
                : allRows.stream().filter(IndicatorDetailQueryController::meetsNumerator).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleId", ruleId);
        body.put("ruleName", data.get("indicatorName"));
        body.put("group", group);
        body.put("statStart", startTime.toString());
        body.put("statEnd", endTime.toString());
        body.put("rowCount", groupRows.size());
        if (groupRows.size() > MAX_ROWS) {
            body.put("rows", groupRows.subList(0, MAX_ROWS));
            body.put("truncated", true);
        } else {
            body.put("rows", groupRows);
        }
        body.put("sqlSource", "mras_extracted");
        body.put("detailSql", extraction.detailSql().strip());
        // 运行绑定：透出卡片金标准与概览 SQL 哈希，供前端/审计核对本次明细与卡片同源。
        body.put("cardNumerator", data.get("cardNumerator"));
        body.put("cardDenominator", data.get("cardDenominator"));
        body.put("overviewSqlHash", data.get("overviewSqlHash"));
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /** 判定明细某行是否命中分子：读 {@code __meets_numerator} 列，1/"1"/"true" 视为命中。 */
    private static boolean meetsNumerator(Map<String, Object> row) {
        Object flag = row.get(MrasDetailSqlExtractor.NUMERATOR_FLAG_COLUMN);
        if (flag instanceof Number number) {
            return number.intValue() == 1;
        }
        if (flag == null) {
            return false;
        }
        String text = flag.toString().strip();
        return "1".equals(text) || "true".equalsIgnoreCase(text);
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

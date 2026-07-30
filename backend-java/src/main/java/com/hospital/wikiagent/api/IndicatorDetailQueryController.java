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
import org.springframework.web.server.ResponseStatusException;

import com.hospital.wikiagent.agent.mras.MrasDetailSqlSynthesizer;
import com.hospital.wikiagent.agent.mras.MrasDetailSqlSynthesizer.DetailSqlPair;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.auth.BearerTokens;
import com.hospital.wikiagent.auth.HospitalAuthService;

/**
 * 指标结果卡片的「明细」按钮接口：按 rule_id + 统计区间直接查询分子/分母患者明细。
 *
 * <p>复用会话内明细链路的同一套能力（小模型合成明细 SQL + 领导知识库患者明细回退），
 * 但不依赖会话上下文，前端卡片可对任意指标、任意统计区间独立调用。</p>
 */
@RestController
@RequestMapping("/api/kb/rules")
public class IndicatorDetailQueryController {

    /** 单次响应最多返回的明细行数，避免大结果集拖垮前端渲染。 */
    private static final int MAX_ROWS = 200;

    private final HospitalAuthService authService;
    private final MrasSqlExecutionService mrasExecution;
    private final MrasDetailSqlSynthesizer detailSynthesizer;

    public IndicatorDetailQueryController(
            HospitalAuthService authService,
            MrasSqlExecutionService mrasExecution,
            MrasDetailSqlSynthesizer detailSynthesizer) {
        this.authService = authService;
        this.mrasExecution = mrasExecution;
        this.detailSynthesizer = detailSynthesizer;
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "group 只能是 numerator 或 denominator");
        }
        if (!mrasExecution.supports(ruleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指标 " + ruleId + " 暂不支持明细查询");
        }
        LocalDateTime startTime = parseStart(start);
        LocalDateTime endTime = parseEnd(end);
        boolean denominator = "denominator".equals(group);
        String queryType = denominator ? "denominator_detail" : "numerator_detail";

        // 优先用合成的分子/分母明细 SQL（每次重新合成，不缓存）；合成或执行失败回退领导知识库患者明细 SQL
        ToolResult result = null;
        String usedDetailSql = null;
        DetailSqlPair pair = detailSynthesizer.synthesize(ruleId);
        if (pair != null) {
            String detailSql = denominator ? pair.denominatorSql() : pair.numeratorSql();
            ToolResult generated = mrasExecution.executeGeneratedDetail(
                    ruleId, detailSql, startTime, endTime, queryType);
            if (generated.ok()) {
                result = generated;
                usedDetailSql = detailSql;
            }
        }
        if (result == null) {
            result = mrasExecution.executePatientDetail(ruleId, profileId, startTime, endTime, null, null);
        }
        if (!result.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "明细查询失败：" + result.summary());
        }

        Map<String, Object> data = result.data();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleId", ruleId);
        body.put("ruleName", data.get("indicatorName"));
        body.put("group", group);
        body.put("statStart", startTime.toString());
        body.put("statEnd", endTime.toString());
        body.put("rowCount", data.get("rowCount"));
        Object rows = data.get("rows");
        if (rows instanceof List<?> rowList && rowList.size() > MAX_ROWS) {
            body.put("rows", rowList.subList(0, MAX_ROWS));
            body.put("truncated", true);
        } else {
            body.put("rows", rows == null ? List.of() : rows);
        }
        // usedDetailSql 为空说明走的是领导知识库患者明细回退，此时分子/分母区分不生效
        body.put("sqlSource", usedDetailSql != null ? "synthesized" : "mras_patient_detail");
        if (usedDetailSql != null) {
            body.put("detailSql", usedDetailSql.strip());
        }
        return body;
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start/end 不能为空");
        }
        String normalized = text.strip().replace('T', ' ');
        try {
            if (normalized.length() <= 10) {
                LocalDate date = LocalDate.parse(normalized);
                return endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            return LocalDateTime.parse(normalized.replace(' ', 'T'));
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间格式不正确：" + text);
        }
    }
}

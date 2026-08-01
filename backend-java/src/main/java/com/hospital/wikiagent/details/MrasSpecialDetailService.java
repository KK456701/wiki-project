package com.hospital.wikiagent.details;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.batch.BatchJobStore.BatchTaskSnapshot;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.MrasDetailKind;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.runtime.ToolResult;

/**
 * 四种非普通比例详情的显式查询计划与统计契约。
 *
 * <p>业务类型由注册表确定，不由 SQL 猜测；每个计划只使用知识库已有目标表、时间窗口和
 * 已保存卡片值。查询执行统一回到 {@link MrasSqlExecutionService}，因此仍经过原有抽取、
 * 模板渲染、只读校验、参数绑定和医院锁。</p>
 */
@Service
public class MrasSpecialDetailService {
    private static final String SURGERY_BASE = """
            FROM MRAS_BUSINESS_SUR_GRADE event (NOLOCK)
            WHERE event.IS_DEL = 0
              AND event.VERSION = 'V2.0'
              AND event.EVENT_AT BETWEEN :marptBeginAt AND :marptEndAt
              AND event.SURG_LEVEL_CODE IN (136618, 136619)
            """;

    private final EntityPageParser entities;
    private final MrasSqlExecutionService execution;

    public MrasSpecialDetailService(
            EntityPageParser entities,
            MrasSqlExecutionService execution) {
        this.entities = entities;
        this.execution = execution;
    }

    public Map<String, Object> details(
            BatchTaskSnapshot task,
            MrasDetailKind kind,
            LocalDateTime start,
            LocalDateTime end) {
        return switch (kind) {
            case SUM_CONTRIBUTION -> sumContribution(task, start, end);
            case MEDIAN_SAMPLE -> medianSamples(task, start, end);
            case DUAL_SOURCE -> dualSource(task, start, end);
            case RATE_COMPARISON -> rateComparison(task, start, end);
            default -> throw error(
                    "DETAIL_KIND_INVALID",
                    "该详情类型不是特殊指标契约。",
                    HttpStatus.BAD_REQUEST);
        };
    }

    private Map<String, Object> sumContribution(
            BatchTaskSnapshot task,
            LocalDateTime start,
            LocalDateTime end) {
        String patient = requirePatientSql(task);
        String sql = """
                SELECT __rescue.*,
                  CAST(COALESCE(__rescue.[抢救成功次数], 0) AS BIGINT)
                    AS "__numerator_contribution",
                  CAST(COALESCE(__rescue.[抢救次数], 0) AS BIGINT)
                    AS "__denominator_contribution"
                FROM (
                %s
                ) __rescue
                """.formatted(patient);
        ToolResult result = execute(task, start, end, Map.of("contributions", sql));
        List<Map<String, Object>> rows = dataset(result, "contributions");
        long numerator = sum(rows, "__numerator_contribution");
        long denominator = sum(rows, "__denominator_contribution");
        requireCounts(task, numerator, denominator);
        Map<String, Object> body = base(task, result, MrasDetailKind.SUM_CONTRIBUTION);
        body.put("numeratorContributionTotal", numerator);
        body.put("denominatorContributionTotal", denominator);
        body.put("rows", rows);
        body.put("rowCount", rows.size());
        return body;
    }

    private Map<String, Object> medianSamples(
            BatchTaskSnapshot task,
            LocalDateTime start,
            LocalDateTime end) {
        // 知识库患者明细附带 EMPLOYEE_INFO 展示关联，但该维表不在真实库中；中位数
        // 概览本身只依赖 MRAS_BUSINESS_CRITICAL_RPT。样本契约必须与概览同源，
        // 因此显式选择概览母集及其真实存在的接收人标识，不把展示维表变成硬依赖。
        String sampleSource = """
                SELECT
                  event.ENCOUNTER_ID,
                  event.CURRENT_DEPT_ID AS 当前科室编码,
                  event.CURRENT_DEPT_NAME AS 当前科室名称,
                  event.IMRN AS 住院号,
                  event.PERSON_NAME AS 患者姓名,
                  event.CURRENT_ADMITTER_NAME AS 责任医师,
                  event.ADMITTED_TO_WARD_AT AS 入区时间,
                  event.WARD_DISCHARGED_AT AS 出区时间,
                  event.CRITICAL_VAL_DTL AS 危急值详情,
                  event.PUBLISH_AT AS 危急值报告时间,
                  event.RECEIVE_TIME AS 危急值接收时间,
                  event.RECEIVE_BY AS 危急值接收人标识,
                  DATEDIFF(
                    MINUTE, event.PUBLISH_AT, event.RECEIVE_TIME
                  ) AS "危急值接收时间-报告时间(分钟)"
                FROM MRAS_BUSINESS_CRITICAL_RPT event (NOLOCK)
                WHERE event.ENCOUNTER_TYPE_CODE = '145235'
                  AND event.ADMITTED_TO_WARD_AT
                    BETWEEN :marptBeginAt AND :marptEndAt
                  AND event.RECEIVE_TIME IS NOT NULL
                  AND event.PUBLISH_AT IS NOT NULL
                """;
        String sql = """
                WITH __sample_source AS (
                %s
                ),
                __sample_values AS (
                  SELECT __sample_source.*,
                    CAST(__sample_source.[危急值接收时间-报告时间(分钟)] AS FLOAT)
                      AS [__sample_minutes]
                  FROM __sample_source
                  WHERE __sample_source.[危急值接收时间-报告时间(分钟)] IS NOT NULL
                ),
                __ranked AS (
                  SELECT __sample_values.*,
                    ROW_NUMBER() OVER (
                      ORDER BY [__sample_minutes], ENCOUNTER_ID
                    ) AS [__sample_order],
                    COUNT_BIG(1) OVER () AS [__sample_count]
                  FROM __sample_values
                )
                SELECT __ranked.*,
                  CASE WHEN [__sample_order] IN (
                    ([__sample_count] + 1) / 2,
                    ([__sample_count] + 2) / 2
                  ) THEN 1 ELSE 0 END AS [__is_median_sample]
                FROM __ranked
                ORDER BY [__sample_order]
                """.formatted(sampleSource);
        ToolResult result = execute(task, start, end, Map.of("samples", sql));
        List<Map<String, Object>> rows = dataset(result, "samples");
        List<Double> middle = rows.stream()
                .filter(row -> longValue(row, "__is_median_sample") == 1L)
                .map(row -> number(row, "__sample_minutes"))
                .filter(value -> value != null)
                .toList();
        Double median = middle.isEmpty()
                ? null
                : middle.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        boolean emptyNoSample = "NO_SAMPLE".equals(task.status()) && rows.isEmpty();
        if (!emptyNoSample && (task.resultValue() == null || median == null
                || Math.abs(task.resultValue() - median) > 0.011d)) {
            throw error(
                    "DETAIL_COUNT_MISMATCH",
                    "中位数样本重算结果与原批次卡片不一致，已拒绝返回。",
                    HttpStatus.CONFLICT);
        }
        Map<String, Object> body = base(task, result, MrasDetailKind.MEDIAN_SAMPLE);
        body.put("medianValue", median == null ? null : BigDecimal.valueOf(median)
                .setScale(2, RoundingMode.HALF_UP));
        body.put("sampleCount", rows.size());
        body.put("rows", rows);
        body.put("rowCount", rows.size());
        return body;
    }

    private Map<String, Object> dualSource(
            BatchTaskSnapshot task,
            LocalDateTime start,
            LocalDateTime end) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("actual", """
                SELECT *
                FROM (
                  SELECT DISTINCT
                    event.CURRENT_DEPT_ID AS "__department_id",
                    event.CURRENT_DEPT_NAME AS "__department_name",
                    event.SURGERY_ID AS "__surgery_id",
                    event.SURG_NAME AS "手术名称",
                    event.SURG_LEVEL_NAME AS "手术等级"
                  %s
                ) __actual_surgery
                """.formatted(SURGERY_BASE));
        // 概览分母在每个实际开展科室上连接同一备案目录后再求和，因此详情也按同一
        // 查询关系显式展开“科室 × 备案术种”，不能只返回一次全院备案目录冒充卡片分母。
        queries.put("registered", """
                WITH __departments AS (
                  SELECT DISTINCT
                    event.CURRENT_DEPT_ID,
                    event.CURRENT_DEPT_NAME
                  %s
                )
                SELECT
                  __departments.CURRENT_DEPT_ID AS "__department_id",
                  __departments.CURRENT_DEPT_NAME AS "__department_name",
                  registry.CLIBASIC_SURGERY_ID AS "__registered_surgery_id",
                  registry.SURGERY_GRADE_CODE AS "手术等级编码",
                  registry.SURGERY_DIR_NO AS "目录编码"
                FROM __departments
                CROSS JOIN CLIBASIC_SURGERY registry (NOLOCK)
                WHERE registry.IS_DEL = 0
                  AND registry.SURGERY_GRADE_CODE IN (136618, 136619)
                  AND registry.SURGERY_DIR_NO IN (957347, 957348)
                  AND registry.ENABLED_FLAG = 1
                """.formatted(SURGERY_BASE));
        ToolResult result = execute(task, start, end, queries);
        List<Map<String, Object>> actual = dataset(result, "actual");
        List<Map<String, Object>> registered = dataset(result, "registered");
        requireCounts(task, actual.size(), registered.size());
        Map<String, Object> body = base(task, result, MrasDetailKind.DUAL_SOURCE);
        body.put("actualCount", actual.size());
        body.put("registeredCount", registered.size());
        body.put("actualRows", actual);
        body.put("registeredRows", registered);
        return body;
    }

    private Map<String, Object> rateComparison(
            BatchTaskSnapshot task,
            LocalDateTime start,
            LocalDateTime end) {
        boolean death = task.ruleId().startsWith("HXZD-012-002");
        String hitJoin = death
                ? ""
                : """
                  LEFT JOIN MRAS_PATIENT_EVENT comp (NOLOCK)
                    ON event.ENCOUNTER_ID = comp.ENCOUNTER_ID
                   AND comp.EVENT_NO = 'MahpComplication'
                   AND comp.IS_DEL = 0
                  """;
        String hit = death
                ? "event.DEATH_FLAG = 98175"
                : "comp.MRAS_PATIENT_EVENT_ID IS NOT NULL";
        String exclusion = death
                ? """
                  NOT EXISTS (
                    SELECT 1 FROM MRAS_BUSINESS_SUR_GRADE higher (NOLOCK)
                    WHERE higher.ENCOUNTER_ID = event.ENCOUNTER_ID
                      AND higher.IS_DEL = 0
                      AND higher.VERSION = 'V2.0'
                      AND higher.SURG_LEVEL_CODE = 136619
                      AND higher.DEATH_FLAG = 98175
                  )
                  """
                : """
                  NOT EXISTS (
                    SELECT 1
                    FROM MRAS_BUSINESS_SUR_GRADE higher (NOLOCK)
                    LEFT JOIN MRAS_PATIENT_EVENT higher_comp (NOLOCK)
                      ON higher.ENCOUNTER_ID = higher_comp.ENCOUNTER_ID
                     AND higher_comp.EVENT_NO = 'MahpComplication'
                     AND higher_comp.IS_DEL = 0
                    WHERE higher.ENCOUNTER_ID = event.ENCOUNTER_ID
                      AND higher.IS_DEL = 0
                      AND higher.VERSION = 'V2.0'
                      AND higher.SURG_LEVEL_CODE = 136619
                      AND higher_comp.MRAS_PATIENT_EVENT_ID IS NOT NULL
                  )
                  """;
        String rowId = death ? "event.MRAS_BUSINESS_SUR_GRADE_ID" : "event.ENCOUNTER_ID";
        String detailColumns = """
                event.ENCOUNTER_ID,
                event.CURRENT_DEPT_ID AS 当前科室编码,
                event.CURRENT_DEPT_NAME AS 当前科室,
                event.PERSON_NAME AS 患者姓名,
                event.IMRN AS 住院号,
                event.SURG_NAME AS 手术名称,
                event.SURG_LEVEL_NAME AS 手术等级
                """;
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("level4Total", rateQuery(
                death ? "" : "DISTINCT", detailColumns + ", " + rowId + " AS \"__detail_id\"",
                hitJoin, "event.SURG_LEVEL_CODE = 136619"));
        queries.put("level4Hit", rateQuery(
                "DISTINCT", detailColumns, hitJoin,
                "event.SURG_LEVEL_CODE = 136619 AND " + hit));
        queries.put("level3Total", rateQuery(
                death ? "" : "DISTINCT", detailColumns + ", " + rowId + " AS \"__detail_id\"",
                hitJoin, "event.SURG_LEVEL_CODE = 136618 AND " + exclusion));
        queries.put("level3Hit", rateQuery(
                "DISTINCT", detailColumns, hitJoin,
                "event.SURG_LEVEL_CODE = 136618 AND " + hit + " AND " + exclusion));
        ToolResult result = execute(task, start, end, queries);
        List<Map<String, Object>> level4Total = dataset(result, "level4Total");
        List<Map<String, Object>> level4Hit = dataset(result, "level4Hit");
        List<Map<String, Object>> level3Total = dataset(result, "level3Total");
        List<Map<String, Object>> level3Hit = dataset(result, "level3Hit");
        String level4Rate = percent(level4Hit.size(), level4Total.size());
        String level3Rate = percent(level3Hit.size(), level3Total.size());
        String[] cardParts = rateParts(task.calculationDisplay());
        String level4Display = level4Total.isEmpty()
                && isNoData(cardParts[0]) ? "无数据" : level4Rate + "%";
        String level3Display = level3Total.isEmpty()
                && isNoData(cardParts[1]) ? "无数据" : level3Rate + "%";
        String display = level4Display + " : " + level3Display;
        if (!sameRateDisplay(display, task.calculationDisplay())) {
            throw error(
                    "DETAIL_COUNT_MISMATCH",
                    "两率四组明细重算结果与原批次卡片不一致，已拒绝返回。",
                    HttpStatus.CONFLICT);
        }
        Map<String, Object> body = base(task, result, MrasDetailKind.RATE_COMPARISON);
        body.put("level4Rate", level4Display);
        body.put("level3Rate", level3Display);
        body.put("resultDisplay", display);
        body.put("level4Hit", level4Hit);
        body.put("level4Total", level4Total);
        body.put("level3Hit", level3Hit);
        body.put("level3Total", level3Total);
        return body;
    }

    private static String rateQuery(
            String distinct,
            String columns,
            String join,
            String predicate) {
        // DBHub 会给顶层 SELECT 注入行数上限；SQL Server 的顶层 SELECT DISTINCT
        // 会被改写成非法的 SELECT TOP ... DISTINCT。把 DISTINCT 保留在派生表内，
        // 顶层只做 SELECT *，既不改变概览的去重语义，也能通过受控查询网关。
        return """
                SELECT *
                FROM (
                  SELECT %s %s
                  FROM MRAS_BUSINESS_SUR_GRADE event (NOLOCK)
                  %s
                  WHERE event.IS_DEL = 0
                    AND event.VERSION = 'V2.0'
                    AND event.EVENT_AT BETWEEN :marptBeginAt AND :marptEndAt
                    AND %s
                ) __rate_detail
                """.formatted(distinct, columns, join, predicate);
    }

    private ToolResult execute(
            BatchTaskSnapshot task,
            LocalDateTime start,
            LocalDateTime end,
            Map<String, String> queries) {
        ToolResult result = execution.executeSpecialDetailQueries(
                task.ruleId(), task.profileId(), queries, start, end);
        if (!result.ok()) {
            throw error(
                    "DETAIL_QUERY_FAILED",
                    "特殊指标明细查询失败：" + result.summary(),
                    HttpStatus.BAD_GATEWAY);
        }
        return result;
    }

    private String requirePatientSql(BatchTaskSnapshot task) {
        EntityPageData entity = task.profileId() == null
                ? null : entities.getEntity(task.profileId());
        if (entity == null) {
            entity = entities.getEntity(task.ruleId());
        }
        if (entity == null || entity.patientDetailSql() == null
                || entity.patientDetailSql().isBlank()) {
            throw error(
                    "DETAIL_UNSUPPORTED",
                    "知识库缺少该特殊指标的患者明细 SQL。",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return MrasSqlExecutionService.stripLeadingTrailingQuotes(entity.patientDetailSql());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataset(ToolResult result, String name) {
        Object datasets = result.data().get("datasets");
        if (datasets instanceof Map<?, ?> map && map.get(name) instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static Map<String, Object> base(
            BatchTaskSnapshot task,
            ToolResult result,
            MrasDetailKind kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchRunId", task.batchRunId());
        body.put("ruleId", task.ruleId());
        body.put("ruleName", task.ruleName());
        body.put("profileId", task.profileId());
        body.put("detailKind", kind.name());
        body.put("detailContractVersion", task.detailContractVersion());
        body.put("statStart", task.statStart());
        body.put("statEnd", task.statEnd());
        body.put("extractionDurationMs", result.data().get("extractionDurationMs"));
        body.put("queryDurationMs", result.data().get("queryDurationMs"));
        body.put("cardResult", task.resultValue());
        body.put("cardResultDisplay", task.calculationDisplay());
        return body;
    }

    private static void requireCounts(
            BatchTaskSnapshot task, long numerator, long denominator) {
        if (task.numeratorCount() == null || task.denominatorCount() == null
                || task.numeratorCount() != numerator
                || task.denominatorCount() != denominator) {
            throw error(
                    "DETAIL_COUNT_MISMATCH",
                    "特殊指标明细重算结果与原批次卡片不一致，已拒绝返回。",
                    HttpStatus.CONFLICT);
        }
    }

    private static long sum(List<Map<String, Object>> rows, String key) {
        long total = 0L;
        for (Map<String, Object> row : rows) {
            total += longValue(row, key);
        }
        return total;
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object raw = lookup(row, key);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return raw == null ? 0L : Long.parseLong(raw.toString());
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static Double number(Map<String, Object> row, String key) {
        Object raw = lookup(row, key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return raw == null ? null : Double.valueOf(raw.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Object lookup(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String percent(int numerator, int denominator) {
        if (denominator == 0) {
            return "0.00";
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static boolean sameRateDisplay(String computed, String card) {
        if (card == null || card.isBlank()) {
            return false;
        }
        String normalizedComputed = computed.replaceAll("\\s+", "");
        String normalizedCard = card.replaceAll("\\s+", "")
                .replace("：", ":");
        return normalizedComputed.equalsIgnoreCase(normalizedCard);
    }

    private static String[] rateParts(String display) {
        String normalized = display == null ? "" : display.replace('：', ':');
        String[] values = normalized.split(":", 2);
        return new String[] {
                values.length > 0 ? values[0].strip() : "",
                values.length > 1 ? values[1].strip() : ""
        };
    }

    private static boolean isNoData(String value) {
        return value != null && value.contains("无数据");
    }

    private static IndicatorDetailException error(
            String code, String message, HttpStatus status) {
        return new IndicatorDetailException(code, message, status);
    }
}

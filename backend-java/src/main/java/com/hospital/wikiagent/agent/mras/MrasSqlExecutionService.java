package com.hospital.wikiagent.agent.mras;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.dbhub.DbHubMcpException;

/**
 * 领导知识库（knowledge-index-mras）概览/科室/明细 SQL 的端到端执行服务。
 *
 * <p>职责边界：从 EntityPageParser 取模板 → MrasTemplateRenderer 解析模板语法 →
 * ReadOnlySqlValidator 只读校验 → SqlParameterBinder 防注入绑定 →
 * IndicatorDatabaseQueryClient 执行。不修改知识库文件、不直接访问网络。</p>
 *
 * <p>与现有 DualDatabaseIndicatorExecutionWorkflow 并行存在，不替换原有链路；
 * 仅当指标在领导知识库中有对应实体页时可用。</p>
 */
@Component
public class MrasSqlExecutionService {

    private static final Logger log = LoggerFactory.getLogger(MrasSqlExecutionService.class);

    private final EntityPageParser entityPageParser;
    private final MrasTemplateRenderer templateRenderer;
    private final MrasParameterMapper parameterMapper;
    private final ReadOnlySqlValidator sqlValidator;
    private final SqlParameterBinder parameterBinder;
    private final IndicatorDatabaseQueryClient databaseQuery;

    public MrasSqlExecutionService(
            EntityPageParser entityPageParser,
            MrasTemplateRenderer templateRenderer,
            MrasParameterMapper parameterMapper,
            ReadOnlySqlValidator sqlValidator,
            SqlParameterBinder parameterBinder,
            IndicatorDatabaseQueryClient databaseQuery) {
        this.entityPageParser = entityPageParser;
        this.templateRenderer = templateRenderer;
        this.parameterMapper = parameterMapper;
        this.sqlValidator = sqlValidator;
        this.parameterBinder = parameterBinder;
        this.databaseQuery = databaseQuery;
    }

    /**
     * 判断指定指标是否在领导知识库中可用。
     */
    public boolean supports(String indicatorCode) {
        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        return entity != null && entity.hasOverviewSql();
    }

    /**
     * 执行领导知识库概览查询。
     *
     * @param indicatorCode 指标编码（如 HXZD-001-001）
     * @param start         统计开始时间
     * @param end           统计结束时间
     * @param deptFilter    科室过滤（可为 null）
     * @param qualifiedFilter 达标状态过滤（可为 null）
     * @return 包含分子/分母/指标值的 ToolResult
     */
    public ToolResult executeOverview(
            String indicatorCode,
            LocalDateTime start,
            LocalDateTime end,
            String deptFilter,
            String qualifiedFilter) {

        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        if (entity == null) {
            return ToolResult.failure("unavailable", "MRAS_ENTITY_NOT_FOUND",
                    "领导知识库中没有指标 " + indicatorCode + " 的实体页。", false);
        }
        if (!entity.hasOverviewSql()) {
            return ToolResult.failure("unavailable", "MRAS_OVERVIEW_SQL_EMPTY",
                    "指标 " + indicatorCode + " 的概览 SQL 尚未实现。", false);
        }

        Map<String, Object> params = parameterMapper.mapParameters(
                start, end, deptFilter, qualifiedFilter);

        return executeSql(
                entity.overviewSql(), params, indicatorCode, "overview",
                entity.name(), entity.dimension());
    }

    /**
     * 执行领导知识库科室统计查询。
     */
    public ToolResult executeDeptStat(
            String indicatorCode,
            LocalDateTime start,
            LocalDateTime end,
            String deptFilter) {

        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        if (entity == null) {
            return ToolResult.failure("unavailable", "MRAS_ENTITY_NOT_FOUND",
                    "领导知识库中没有指标 " + indicatorCode + " 的实体页。", false);
        }
        if (entity.deptStatSql() == null || entity.deptStatSql().isBlank()) {
            return ToolResult.failure("unavailable", "MRAS_DEPT_SQL_EMPTY",
                    "指标 " + indicatorCode + " 的科室统计 SQL 尚未实现。", false);
        }

        Map<String, Object> params = parameterMapper.mapParameters(
                start, end, deptFilter, null);

        return executeSql(
                entity.deptStatSql(), params, indicatorCode, "dept_stat",
                entity.name(), entity.dimension());
    }

    /**
     * 执行领导知识库患者明细查询。
     */
    public ToolResult executePatientDetail(
            String indicatorCode,
            LocalDateTime start,
            LocalDateTime end,
            String deptFilter,
            String qualifiedFilter) {

        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        if (entity == null) {
            return ToolResult.failure("unavailable", "MRAS_ENTITY_NOT_FOUND",
                    "领导知识库中没有指标 " + indicatorCode + " 的实体页。", false);
        }
        if (entity.patientDetailSql() == null || entity.patientDetailSql().isBlank()) {
            return ToolResult.failure("unavailable", "MRAS_DETAIL_SQL_EMPTY",
                    "指标 " + indicatorCode + " 的患者明细 SQL 尚未实现。", false);
        }

        Map<String, Object> params = parameterMapper.mapParameters(
                start, end, deptFilter, qualifiedFilter);

        return executeSql(
                entity.patientDetailSql(), params, indicatorCode, "patient_detail",
                entity.name(), entity.dimension());
    }

    /**
     * 获取指标的元数据上下文（定义、口径、数据来源、监测参数），供 LLM 解释用。
     */
    public Map<String, String> getExplanationContext(String indicatorCode) {
        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        if (entity == null) {
            return Map.of();
        }
        Map<String, String> context = new LinkedHashMap<>();
        context.put("indicator_code", entity.code());
        context.put("indicator_name", entity.name());
        context.put("definition", entity.definition());
        context.put("formula", entity.formula());
        context.put("caliber", entity.caliber());
        context.put("data_source", entity.dataSource());
        context.put("monitor_params", entity.monitorParams());
        return context;
    }

    private ToolResult executeSql(
            String templateSql,
            Map<String, Object> params,
            String indicatorCode,
            String queryType,
            String indicatorName,
            String dimension) {

        // 第一步：模板解析（#ETC/#EQUALS + 方言修正），保留命名参数
        String renderedSql;
        try {
            renderedSql = templateRenderer.renderTemplate(templateSql, params);
        } catch (RuntimeException exception) {
            log.warn("领导知识库模板渲染失败 {} {}: {}", indicatorCode, queryType,
                    exception.getMessage());
            return ToolResult.failure("validation_failed", "MRAS_TEMPLATE_RENDER_FAILED",
                    "领导知识库 SQL 模板渲染失败。", false);
        }
        if (renderedSql.isBlank()) {
            return ToolResult.failure("validation_failed", "MRAS_TEMPLATE_EMPTY",
                    "领导知识库 SQL 模板渲染结果为空。", false);
        }

        // 第二步：只读安全校验
        ReadOnlySqlValidator.ValidationResult validation =
                sqlValidator.validateReadOnly(renderedSql);
        if (!validation.ok()) {
            log.warn("领导知识库 SQL 校验未通过 {} {}: {}", indicatorCode, queryType,
                    validation.message());
            return ToolResult.failure("validation_failed", "MRAS_SQL_VALIDATION_FAILED",
                    "领导知识库 SQL 未通过只读安全校验: " + validation.message(), false);
        }

        // 第三步：参数绑定（防注入）
        String executableSql;
        try {
            executableSql = parameterBinder.bind(renderedSql, params);
        } catch (RuntimeException exception) {
            return ToolResult.failure("validation_failed", "MRAS_PARAMETER_BIND_FAILED",
                    "领导知识库 SQL 参数绑定失败: " + exception.getMessage(), false);
        }

        // 第四步：执行查询
        long started = System.nanoTime();
        List<Map<String, Object>> rows;
        try {
            rows = databaseQuery.execute(DatabaseRole.REAL, executableSql);
        } catch (DbHubMcpException exception) {
            // 瞬态连接失败重试一次
            try {
                rows = databaseQuery.execute(DatabaseRole.REAL, executableSql);
            } catch (RuntimeException retryException) {
                return ToolResult.failure("error", "MRAS_QUERY_FAILED",
                        "领导知识库查询执行失败（已重试）。", true);
            }
        } catch (RuntimeException exception) {
            return ToolResult.failure("error", "MRAS_QUERY_FAILED",
                    "领导知识库查询执行失败。", true);
        }
        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);

        // 第五步：解析结果
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("indicator_code", indicatorCode);
        data.put("indicator_name", indicatorName);
        data.put("dimension", dimension);
        data.put("query_type", queryType);
        data.put("duration_ms", durationMs);
        data.put("sql_source", "mras");
        data.put("row_count", rows.size());

        if ("overview".equals(queryType)) {
            parseOverviewResult(rows, data);
        } else {
            data.put("rows", rows);
        }

        String summary = "overview".equals(queryType)
                ? "领导知识库概览计算完成。"
                : "patient_detail".equals(queryType)
                        ? "领导知识库患者明细查询完成。"
                        : "领导知识库科室统计查询完成。";
        return ToolResult.success("MRAS_QUERY_COMPLETED", summary, data);
    }

    /**
     * 解析概览查询结果行，提取分子/分母/指标值/目标值。
     * 领导知识库概览 SQL 返回中文列名（如 "分子...", "分母...", "监测情况", "目标值"）。
     */
    private void parseOverviewResult(List<Map<String, Object>> rows, Map<String, Object> data) {
        if (rows.isEmpty()) {
            data.put("status", "empty");
            data.put("result_value", null);
            data.put("numerator_count", 0L);
            data.put("denominator_count", 0L);
            data.put("no_sample", true);
            return;
        }
        Map<String, Object> first = rows.get(0);
        data.put("status", "success");

        // 尝试从中文列名提取分子/分母
        Long numerator = findLongByPrefix(first, "分子");
        Long denominator = findLongByPrefix(first, "分母");
        Number resultValue = findNumberByContains(first, "监测情况");
        Number targetValue = findNumberByContains(first, "目标值");
        String qualified = findStringByContains(first, "是否达标");

        if (numerator == null) {
            numerator = getLong(first, "numerator_count");
        }
        if (denominator == null) {
            denominator = getLong(first, "denominator_count");
        }
        if (resultValue == null && numerator != null && denominator != null
                && denominator > 0) {
            resultValue = BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        data.put("numerator_count", numerator);
        data.put("denominator_count", denominator);
        data.put("result_value", resultValue);
        data.put("target_value", targetValue);
        data.put("qualified_label", qualified);
        data.put("no_sample", denominator != null && denominator == 0);
        data.put("raw_first_row", first);
    }

    private static Long findLongByPrefix(Map<String, Object> row, String prefix) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                return toLong(entry.getValue());
            }
        }
        return null;
    }

    private static Number findNumberByContains(Map<String, Object> row, String keyword) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().contains(keyword)) {
                return toNumber(entry.getValue());
            }
        }
        return null;
    }

    private static String findStringByContains(Map<String, Object> row, String keyword) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().contains(keyword)) {
                return entry.getValue() == null ? null : entry.getValue().toString();
            }
        }
        return null;
    }

    private static Long getLong(Map<String, Object> row, String key) {
        return toLong(row.get(key));
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Number toNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number;
        }
        try {
            return new BigDecimal(value.toString().strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}

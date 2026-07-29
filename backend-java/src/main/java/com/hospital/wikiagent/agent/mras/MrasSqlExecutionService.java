package com.hospital.wikiagent.agent.mras;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.sql.DatabaseRole;
import com.hospital.wikiagent.agent.sql.IndicatorDatabaseQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.dto.SyncDataDto;
import com.hospital.wikiagent.dto.TableDataDto;
import com.hospital.wikiagent.service.SyncDataService;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;

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
    private final SyncDataService syncDataService; // 可为 null（sqlserver 未启用时）
    private final SqlServerProperties sqlServerProperties;

    public MrasSqlExecutionService(
            EntityPageParser entityPageParser,
            MrasTemplateRenderer templateRenderer,
            MrasParameterMapper parameterMapper,
            ReadOnlySqlValidator sqlValidator,
            SqlParameterBinder parameterBinder,
            IndicatorDatabaseQueryClient databaseQuery,
            ObjectProvider<SyncDataService> syncDataProvider,
            SqlServerProperties sqlServerProperties) {
        this.entityPageParser = entityPageParser;
        this.templateRenderer = templateRenderer;
        this.parameterMapper = parameterMapper;
        this.sqlValidator = sqlValidator;
        this.parameterBinder = parameterBinder;
        this.databaseQuery = databaseQuery;
        this.syncDataService = syncDataProvider.getIfAvailable();
        this.sqlServerProperties = sqlServerProperties;
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

        // 查询前先抽取数据到 winex_aima；失败时把警告随结果透传，不再静默。抽取独立计时供 trace 归属。
        long extractStarted = System.currentTimeMillis();
        String extractionWarning = ensureExtracted(entity, start, end);
        long extractionDurationMs = System.currentTimeMillis() - extractStarted;

        return executeSql(
                entity.overviewSql(), params, indicatorCode, "overview",
                entity.name(), entity.dimension(), true, extractionWarning, extractionDurationMs);
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

        // 查询前先抽取数据到 winex_aima；失败时把警告随结果透传，不再静默
        long deptExtractStarted = System.currentTimeMillis();
        String extractionWarning = ensureExtracted(entity, start, end);
        long deptExtractionMs = System.currentTimeMillis() - deptExtractStarted;

        return executeSql(
                entity.deptStatSql(), params, indicatorCode, "dept_stat",
                entity.name(), entity.dimension(), true, extractionWarning, deptExtractionMs);
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

        // 查询前先抽取数据到 winex_aima；失败时把警告随结果透传，不再静默
        long detailExtractStarted = System.currentTimeMillis();
        String extractionWarning = ensureExtracted(entity, start, end);
        long detailExtractionMs = System.currentTimeMillis() - detailExtractStarted;

        return executeSql(
                entity.patientDetailSql(), params, indicatorCode, "patient_detail",
                entity.name(), entity.dimension(), true, extractionWarning, detailExtractionMs);
    }

    /**
     * 执行由小模型合成、已通过只读校验的分子/分母明细 SQL。
     *
     * <p>生成的 SQL 仍含 {@code :marptBeginAt/:marptEndAt} 命名参数，复用标准
     * 执行链路（模板修正→剥引号→只读校验→参数绑定→DBHub 执行）。</p>
     *
     * @param indicatorCode 指标编码
     * @param detailSql     合成后的明细 SQL（含命名时间参数）
     * @param start         统计开始时间
     * @param end           统计结束时间
     * @param queryType     查询类型（denominator_detail / numerator_detail）
     */
    public ToolResult executeGeneratedDetail(
            String indicatorCode,
            String detailSql,
            LocalDateTime start,
            LocalDateTime end,
            String queryType) {

        if (detailSql == null || detailSql.isBlank()) {
            return ToolResult.failure("unavailable", "MRAS_GENERATED_SQL_EMPTY",
                    "合成的明细 SQL 为空。", false);
        }
        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        String indicatorName = entity == null ? indicatorCode : entity.name();
        String dimension = entity == null ? null : entity.dimension();

        Map<String, Object> params = parameterMapper.mapParameters(
                start, end, null, null);

        return executeSql(
                detailSql, params, indicatorCode, queryType, indicatorName, dimension, false, null, -1);
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
            String dimension,
            boolean stripQuotes,
            String extractionWarning,
            long extractionDurationMs) {

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
        // 知识库部分 SQL 文件有前导双引号（如 "SELECT），需剥离；
        // 小模型合成的 SQL 已是干净语句（结尾可能是字符串字面量闭合引号），不能剥离。
        if (stripQuotes) {
            renderedSql = stripLeadingTrailingQuotes(renderedSql);
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
            log.warn("领导知识库查询失败 {} {}: {}", indicatorCode, queryType, exception.getMessage());
            // 瞬态连接失败重试一次
            try {
                rows = databaseQuery.execute(DatabaseRole.REAL, executableSql);
            } catch (RuntimeException retryException) {
                log.error("领导知识库查询重试仍失败 {} {}: {}", indicatorCode, queryType, retryException.getMessage());
                return ToolResult.failure("error", "MRAS_QUERY_FAILED",
                        "领导知识库查询执行失败（已重试）。", true);
            }
        } catch (RuntimeException exception) {
            log.warn("领导知识库查询异常 {} {}: {}", indicatorCode, queryType, exception.getMessage());
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
        if (extractionDurationMs >= 0) {
            // 抽取与 SQL 执行分开计时，供上层 trace 节点正确归属耗时
            data.put("extraction_duration_ms", extractionDurationMs);
        }
        data.put("sql_source", "mras");
        data.put("row_count", rows.size());
        if (extractionWarning != null) {
            // 抽取失败时结果基于中间表旧数据，必须随结果提醒用户
            data.put("extraction_warning", extractionWarning);
        }

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

    // ==================== 抽取步骤 ====================

    /**
     * 查询前确保数据已抽取到 winex_aima：构建 SyncDataDto 并转调 SyncDataService。
     *
     * <p>hospitalSOID 从配置 wiki.sqlserver.hospital-soid 读取（当前写死 991827）。
     * 抽取失败不阻断查询，但返回失败原因供调用方随结果透传给用户。</p>
     *
     * @return null 表示抽取成功或合法跳过；非 null 为失败原因
     */
    private String ensureExtracted(EntityPageData entity, LocalDateTime start, LocalDateTime end) {
        if (syncDataService == null) {
            log.debug("SyncDataService 未启用，跳过 MRAS 抽取");
            return null;
        }
        if (!entity.canExtract()) {
            log.debug("指标 {} 缺少源表 SQL 或目标表，跳过抽取", entity.code());
            return null;
        }
        Long hospitalSoid = sqlServerProperties.getHospitalSoid();
        if (hospitalSoid == null) {
            log.warn("未配置 wiki.sqlserver.hospital-soid，跳过 MRAS 抽取");
            return "未配置医院 SOID，本次未抽取源库数据";
        }

        try {
            SyncDataDto dto = new SyncDataDto();
            dto.setHospitalSOID(hospitalSoid);

            // eventDataList：事件中间表
            TableDataDto eventData = new TableDataDto();
            eventData.setEventNo(entity.eventNo());
            eventData.setTable(entity.targetTable());
            eventData.setSqlScript(entity.sourceTableSql());
            eventData.setStartTime(toDate(start));
            eventData.setEndTime(toDate(end));
            dto.setEventDataList(List.of(eventData));

            // bizDataList：业务依赖表
            if (entity.bizTables() != null && !entity.bizTables().isEmpty()) {
                List<TableDataDto> bizList = new ArrayList<>();
                for (String table : entity.bizTables()) {
                    TableDataDto biz = new TableDataDto();
                    biz.setTable(table);
                    bizList.add(biz);
                }
                dto.setBizDataList(bizList);
            }

            log.info("MRAS 指标 {} 开始抽取: targetTable={}, bizTables={} ({} ~ {})",
                    entity.code(), entity.targetTable(), entity.bizTables(), start, end);
            syncDataService.syncEventData(dto);
            log.info("MRAS 指标 {} 抽取完成", entity.code());
            return null;
        } catch (Exception exception) {
            log.warn("MRAS 指标 {} 抽取失败（不阻断查询，警告随结果透传）: {}",
                    entity.code(), exception.getMessage());
            return exception.getMessage();
        }
    }

    private static Date toDate(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
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

    /**
     * 剥离 SQL 前后的引号。
     * 知识库部分文件的 SQL 块以 "SELECT 或 '-- 或 'WITH 开头、以 '" 结尾，
     * 这是 Markdown 格式问题，需要在执行前剥离。
     * 公开供 MrasRuleKnowledgeSource 在输出 standard_sql 前做同口径清洗。
     */
    public static String stripLeadingTrailingQuotes(String sql) {
        String result = sql.strip();
        // 剥离前导引号（可能是 " 或 ' 或 "' 组合）
        while (result.startsWith("\"") || result.startsWith("'")) {
            // 保留 "" 转义序列
            if (result.startsWith("\"\"")) {
                break;
            }
            result = result.substring(1);
        }
        // 剥离尾部引号（可能是 '" 或 " 或 '）
        while (result.endsWith("\"") || result.endsWith("'")) {
            // 保留 "" 转义序列
            if (result.endsWith("\"\"")) {
                break;
            }
            result = result.substring(0, result.length() - 1);
        }
        return result.strip();
    }
}

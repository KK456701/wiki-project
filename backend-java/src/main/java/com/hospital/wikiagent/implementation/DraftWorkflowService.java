package com.hospital.wikiagent.implementation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.sql.IndicatorBusinessQueryClient;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlObjectRepository;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;

/**
 * 编排 {@code DraftWorkflowService} 对应的业务流程，并集中维护事务与安全边界。
 *
 * <p>该服务负责按业务顺序组合依赖，并把可预期失败转换为稳定错误语义。它不允许模型直接访问数据库，也不允许上层绕过策略、Evidence 或医院隔离边界。</p>
 */
@Service
public class DraftWorkflowService {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IndicatorDraftRepository drafts;
    private final DraftSqlPlanRenderer renderer;
    private final ReadOnlySqlValidator validator;
    private final SqlObjectRepository sqlObjects;
    private final SqlParameterBinder binder;
    private final IndicatorBusinessQueryClient businessQuery;

    public DraftWorkflowService(
            IndicatorDraftRepository drafts, DraftSqlPlanRenderer renderer,
            ReadOnlySqlValidator validator, SqlObjectRepository sqlObjects,
            SqlParameterBinder binder, IndicatorBusinessQueryClient businessQuery) {
        this.drafts = drafts;
        this.renderer = renderer;
        this.validator = validator;
        this.sqlObjects = sqlObjects;
        this.binder = binder;
        this.businessQuery = businessQuery;
    }

    public Map<String, Object> generateSql(
            String draftId, String hospitalId, int expectedVersion, String actorId) {
        Map<String, Object> draft = drafts.requireDraft(draftId, hospitalId);
        if (!"metadata_ready".equals(draft.get("status"))) {
            throw new ImplementationException("DRAFT_STATUS_INVALID",
                    "只有元数据已确认的设计稿才能生成 SQL。", 409);
        }
        DraftSqlPlanRenderer.RenderedSql rendered = renderer.render(
                map(draft.get("sql_plan")), map(draft.get("field_mapping")));
        ReadOnlySqlValidator.ValidationResult validation = validator.validate(rendered.sql(), rendered.mainTable());
        if (!validation.ok()) {
            throw new ImplementationException("DRAFT_SQL_VALIDATION_FAILED",
                    "SQL 安全校验未通过：" + validation.message(), 400);
        }
        String sqlId = id("SQL_");
        sqlObjects.saveGeneratedDraft(sqlId, hospitalId, text(draft.get("proposed_index_code")),
                rendered.dialect(), rendered.sql(), validation.message(),
                "draft:" + draftId + ":v" + expectedVersion);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("current_sql", rendered.sql());
        changes.put("sql_params", rendered.params());
        changes.put("sql_id", sqlId);
        changes.put("trial_result", Map.of());
        changes.put("trial_draft_version", null);
        return drafts.workflowTransition(draftId, hospitalId, expectedVersion,
                "metadata_ready", "sql_ready", changes, actorId, "sql_generated");
    }

    public Map<String, Object> trialRun(
            String draftId, String hospitalId, int expectedVersion,
            String statStartTime, String statEndTime, String actorId) {
        Map<String, Object> draft = drafts.requireDraft(draftId, hospitalId);
        if (!"sql_ready".equals(draft.get("status"))
                || text(draft.get("current_sql")).isBlank() || text(draft.get("sql_id")).isBlank()) {
            throw new ImplementationException("DRAFT_STATUS_INVALID", "请先为当前版本生成并校验 SQL。", 409);
        }
        String start = normalizeTime(statStartTime);
        String end = normalizeTime(statEndTime);
        if (!LocalDateTime.parse(start, SQL_TIME).isBefore(LocalDateTime.parse(end, SQL_TIME))) {
            throw new ImplementationException("DRAFT_PERIOD_INVALID", "统计开始时间必须早于结束时间。", 400);
        }
        String mainTable = text(map(draft.get("sql_plan")).get("main_table"));
        String sql = text(draft.get("current_sql"));
        if (!validator.validate(sql, mainTable).ok()) {
            throw new ImplementationException("DRAFT_SQL_REVALIDATION_FAILED",
                    "SQL 在试运行前未通过二次只读安全校验。", 409);
        }
        Map<String, Object> params = new LinkedHashMap<>(map(draft.get("sql_params")));
        params.put("hospital_id", hospitalId);
        params.put("start_time", start);
        params.put("end_time", end);
        String executable;
        try {
            executable = binder.bind(sql, params);
        } catch (RuntimeException exception) {
            throw new ImplementationException("DRAFT_SQL_PARAMETER_MISSING", "SQL 运行参数不完整。", 400);
        }

        String runId = id("RUN_");
        long started = System.nanoTime();
        try {
            List<Map<String, Object>> rows = businessQuery.execute(executable);
            long duration = duration(started);
            Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
            Number resultValue = number(value(first, "index_value"));
            Long numerator = longValue(value(first, "numerator_count"));
            Long denominator = longValue(value(first, "denominator_count"));
            if (denominator == null) denominator = longValue(value(first, "sample_count"));
            if (resultValue == null) {
                sqlObjects.saveDraftRun(runId, text(draft.get("sql_id")), hospitalId,
                        text(draft.get("proposed_index_code")), start, end, "empty", null,
                        numerator, denominator, "", duration, actorId, runContext(draftId, expectedVersion));
                throw new ImplementationException("DRAFT_TRIAL_EMPTY", "SQL 试运行未返回可用指标值。", 409);
            }
            Map<String, Object> trial = new LinkedHashMap<>();
            trial.put("run_id", runId);
            trial.put("sql_id", draft.get("sql_id"));
            trial.put("status", "success");
            trial.put("result_value", resultValue);
            trial.put("numerator_count", numerator);
            trial.put("denominator_count", denominator);
            trial.put("source", businessQuery.sourceId());
            trial.put("stat_start_time", start);
            trial.put("stat_end_time", end);
            trial.put("duration_ms", duration);
            sqlObjects.saveDraftRun(runId, text(draft.get("sql_id")), hospitalId,
                    text(draft.get("proposed_index_code")), start, end, "success", resultValue,
                    numerator, denominator, "", duration, actorId, runContext(draftId, expectedVersion));
            return drafts.workflowTransition(draftId, hospitalId, expectedVersion,
                    "sql_ready", "trial_passed", Map.of(
                            "trial_result", trial,
                            "trial_draft_version", expectedVersion + 1),
                    actorId, "trial_run");
        } catch (ImplementationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            long duration = duration(started);
            try {
                sqlObjects.saveDraftRun(runId, text(draft.get("sql_id")), hospitalId,
                        text(draft.get("proposed_index_code")), start, end, "failed", null,
                        null, null, "DBHub query failed", duration, actorId,
                        runContext(draftId, expectedVersion));
            } catch (RuntimeException ignored) {
                // DBHub 错误优先，运行日志失败不能覆盖主错误。
            }
            throw new ImplementationException("DRAFT_TRIAL_FAILED", "SQL 试运行失败，未获得可用聚合结果。", 502);
        }
    }

    private static Map<String, Object> runContext(String draftId, int version) {
        return Map.of("source", "indicator_draft", "draft_id", draftId, "draft_version", version);
    }

    private static String normalizeTime(String value) {
        try {
            return LocalDateTime.parse(text(value).replace(' ', 'T')).format(SQL_TIME);
        } catch (RuntimeException exception) {
            throw new ImplementationException("DRAFT_PERIOD_INVALID", "统计时间格式无效。", 400);
        }
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equals(entry.getKey().toLowerCase(Locale.ROOT))) return entry.getValue();
        }
        return null;
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        try { return value == null ? null : Double.valueOf(value.toString()); }
        catch (NumberFormatException exception) { return null; }
    }

    private static Long longValue(Object value) {
        Number number = number(value);
        return number == null ? null : number.longValue();
    }

    private static long duration(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private static String id(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    private static String text(Object value) { return value == null ? "" : value.toString().strip(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
}

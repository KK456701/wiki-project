package com.hospital.wikiagent.agent.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.agent.planning.StatPeriodPolicy;
import com.hospital.wikiagent.dbhub.DatabaseSourceException;
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.rules.RuleReadRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 根据 Wiki 中的规则规格确定性准备 SQL 对象，并仅通过 DBHub 执行受控只读试运行。
 * 浏览器和模型均不能向这里提交任意 SQL 正文。
 *
 * <p>能力只能经 ToolGateway 的权限、参数和重复调用检查后执行，不能由模型绕过网关直接调用。返回值必须形成可验证 Evidence，再交给最终答案使用。</p>
 */
@Component
public class IndicatorSqlTools {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration SQL_TTL = Duration.ofMinutes(30);

    private final RuleReadRepository rules;
    private final SqlObjectRepository objects;
    private final SqlTemplateRenderer renderer;
    private final ReadOnlySqlValidator validator;
    private final SqlParameterBinder binder;
    private final IndicatorBusinessQueryClient businessQuery;
    private final ObjectMapper objectMapper;
    private DualDatabaseIndicatorExecutionWorkflow dualDatabaseWorkflow;

    public IndicatorSqlTools(
            RuleReadRepository rules,
            SqlObjectRepository objects,
            SqlTemplateRenderer renderer,
            ReadOnlySqlValidator validator,
            SqlParameterBinder binder,
            IndicatorBusinessQueryClient businessQuery,
            ObjectMapper objectMapper) {
        this.rules = rules;
        this.objects = objects;
        this.renderer = renderer;
        this.validator = validator;
        this.binder = binder;
        this.businessQuery = businessQuery;
        this.objectMapper = objectMapper;
    }

    /**
     * 双库执行是可选的增强链路。使用 setter 注入可以让现有单库测试和禁用模式保持
     * 原构造契约；生产 Spring 容器存在 Workflow 时会自动完成注入。
     */
    @Autowired(required = false)
    void setDualDatabaseWorkflow(DualDatabaseIndicatorExecutionWorkflow dualDatabaseWorkflow) {
        this.dualDatabaseWorkflow = dualDatabaseWorkflow;
    }

    public ToolResult inspect(InspectInput input, ToolExecutionContext context) {
        Map<String, Object> rule = rules.effectiveRule(input.ruleId(), context.agentContext().hospitalId());
        Map<String, Object> mapping = rules.fieldMapping(input.ruleId(), context.agentContext().hospitalId());
        Inspection inspection = inspection(rule, mapping);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", input.ruleId());
        data.put("hospitalId", context.agentContext().hospitalId());
        data.put("status", mapping.get("status"));
        data.put("mappingStatus", mapping.get("status"));
        data.put("dialect", mapping.get("dialect"));
        data.put("mainTable", mapping.get("mainTable"));
        data.put("mappedFields", inspection.mappedFields());
        data.put("requiredBusinessFields", inspection.requiredFields());
        data.put("missingMappings", inspection.missingMappings());
        data.put("unconfirmedMappings", inspection.unconfirmedMappings());
        data.put("missingColumns", inspection.missingColumns());
        data.put("typeMismatches", inspection.typeMismatches());
        data.put("missingRelations", inspection.missingRelations());
        data.put("mappingItems", safeItems(listOfMaps(mapping.get("items"))));
        data.put("relations", safeRelations(listOfMaps(mapping.get("relations"))));
        data.put("queryProfile", mapping.get("queryProfile"));
        data.put("sqlStatus", rule.getOrDefault("sqlStatus", "unavailable"));
        data.put("profileId", rule.get("profileId"));
        data.put("executionStatus", rule.get("executionStatus"));
        data.put("executionBlockers", rule.get("executionBlockers"));
        String summary = !"executable".equals(text(rule.get("executionStatus")))
                ? "当前口径仅支持规则解释，尚未开放数据库执行。"
                : inspection.ready()
                        ? "指标实施映射已确认。"
                        : "指标实施仍有缺失或未确认映射。";
        return ToolResult.success("IMPLEMENTATION_INSPECTED", summary, data);
    }

    public ToolResult prepare(PrepareInput input, ToolExecutionContext context) {
        return prepareInternal(
                input, Map.of(), Map.of(), input.profileId(), context);
    }

    /**
     * 为分层差异诊断准备一个受治理的候选口径 SQL 对象。
     *
     * <p>候选只能覆盖当前规则已声明的参数，SQL 模板、字段映射和验证流程仍与正常试运行
     * 完全一致。这样历史口径可以做反事实计算，但不能借机注入新字段或任意 SQL。</p>
     */
    public ToolResult prepareDiagnostic(
            PrepareInput input,
            String profileId,
            Map<String, Object> parameterOverrides,
            ToolExecutionContext context) {
        return prepareDiagnostic(
                input, profileId, parameterOverrides, Map.of(), context);
    }

    /**
     * 为候选口径额外应用受控的“业务字段角色替换”。
     *
     * <p>配置值只能引用当前医院已经确认的字段角色，例如把 {@code period_time} 和
     * {@code admit_time} 都指向 {@code ward_entry_time}。这里不接受物理表字段名，
     * 因而不能通过诊断配置绕过 Wiki 字段映射或注入任意 SQL。</p>
     */
    public ToolResult prepareDiagnostic(
            PrepareInput input,
            String profileId,
            Map<String, Object> parameterOverrides,
            Map<String, Object> fieldRoleOverrides,
            ToolExecutionContext context) {
        String normalizedProfile = profileId == null ? "" : profileId.strip();
        if (normalizedProfile.isBlank() || normalizedProfile.length() > 128) {
            return failure("validation_failed", "DIAGNOSIS_PROFILE_INVALID",
                    "候选口径编号无效。", false);
        }
        return prepareInternal(
                input,
                parameterOverrides == null ? Map.of() : Map.copyOf(parameterOverrides),
                fieldRoleOverrides == null ? Map.of() : Map.copyOf(fieldRoleOverrides),
                normalizedProfile,
                context);
    }

    private ToolResult prepareInternal(
            PrepareInput input,
            Map<String, Object> parameterOverrides,
            Map<String, Object> fieldRoleOverrides,
            String diagnosticProfileId,
            ToolExecutionContext context) {
        AgentRunState state = context.runState();
        if (state.currentRuleId() == null || !state.currentRuleId().equals(input.ruleId())) {
            return failure("validation_failed", "RULE_NOT_VERIFIED", "该指标尚未经过规则搜索或读取，不能准备 SQL。", false);
        }
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(input.statStartTime());
            end = LocalDateTime.parse(input.statEndTime());
        } catch (RuntimeException exception) {
            return failure("validation_failed", "STAT_PERIOD_INVALID", "统计时间格式无效。", false);
        }
        StatPeriodPolicy.Validation period = StatPeriodPolicy.validate(
                start, end, false);
        if (!period.ok()) {
            return failure("validation_failed", period.code(), period.message(), false);
        }

        Map<String, Object> rule = rules.effectiveRule(
                input.ruleId(), context.agentContext().hospitalId(), diagnosticProfileId);
        boolean fullyExecutable =
                "executable".equals(text(rule.get("executionStatus")));
        boolean overviewStaticRuntime =
                Boolean.TRUE.equals(rule.get("overviewRuntimeEligible"));
        if (!fullyExecutable) {
            /*
             * “给我 SQL”与“执行 SQL”是两种不同能力。对于 documentation_only
             * Profile，明确的 SQL_PREPARE 计划只查看知识库原稿。普通指标计算允许
             * 使用已通过静态门禁、且具备确定性结果列映射的 overview SQL；计算时会
             * 先刷新真实库快照，再仅在真实库执行概览 SQL。候选口径和
             * 诊断仍必须使用完整执行契约，不能借概览试算绕过治理边界。
             */
            if (diagnosticProfileId == null
                    && PlanIntent.INDICATOR_SQL_PREPARE.value().equals(state.lastIntent())) {
                return prepareReferenceOnly(input, start, end, rule, context);
            }
            if (!overviewStaticRuntime) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ruleId", input.ruleId());
                data.put("profileId", rule.get("profileId"));
                data.put("executionStatus", rule.get("executionStatus"));
                data.put("executionBlockers", rule.get("executionBlockers"));
                return failure("unavailable", "PROFILE_NOT_EXECUTABLE",
                        "当前口径仅支持定义和公式解释，尚未具备可安全试算的概览 SQL 与结果列契约。",
                        false, data);
            }
        }
        Map<String, Object> rawMapping = rules.fieldMapping(
                input.ruleId(), context.agentContext().hospitalId(), diagnosticProfileId);
        Map<String, Object> mapping = withExecutionDefaults(rawMapping);
        try {
            mapping = applyDiagnosticFieldRoleOverrides(mapping, fieldRoleOverrides);
        } catch (IllegalArgumentException exception) {
            return failure("validation_failed", "DIAGNOSIS_PROFILE_FIELD_INVALID",
                    exception.getMessage(), false);
        }
        Inspection inspection = inspection(rule, mapping);
        if (!overviewStaticRuntime && !inspection.ready()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("missingMappings", inspection.missingMappings());
            data.put("unconfirmedMappings", inspection.unconfirmedMappings());
            data.put("missingColumns", inspection.missingColumns());
            data.put("typeMismatches", inspection.typeMismatches());
            data.put("missingRelations", inspection.missingRelations());
            return failure("validation_failed", "FIELD_PRECHECK_FAILED",
                    "字段映射或元数据预检查未通过，暂不能准备 SQL。", false, data);
        }
        String template = text(rule.get("standardSql"));
        if (template.isBlank()) {
            return failure("validation_failed", "SQL_TEMPLATE_UNAVAILABLE", "当前生效规则没有可用 SQL 模板。", false);
        }

        String sql;
        try {
            sql = normalizeKnownSqlArtifacts(renderer.render(
                    template, objectMap(mapping.get("fields")), text(mapping.get("mainTable"))));
        } catch (RuntimeException exception) {
            return failure("validation_failed", "SQL_TEMPLATE_RENDER_FAILED", "SQL 模板无法根据已确认映射完成渲染。", false);
        }
        ReadOnlySqlValidator.ValidationResult validation = overviewStaticRuntime
                ? validator.validateReadOnly(sql)
                : validator.validate(sql, text(mapping.get("mainTable")));
        if (!validation.ok()) {
            return failure("validation_failed", "SQL_VALIDATION_FAILED",
                    "生成的 SQL 未通过只读安全校验，不能进入试运行。", false);
        }

        Map<String, Object> params = objectMap(rule.get("effectiveParams"));
        if (!params.keySet().containsAll(parameterOverrides.keySet())) {
            return failure("validation_failed", "DIAGNOSIS_PROFILE_PARAMETER_INVALID",
                    "候选口径包含当前规则未声明的参数。", false);
        }
        for (Map.Entry<String, Object> entry : parameterOverrides.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                return failure("validation_failed", "DIAGNOSIS_PROFILE_PARAMETER_INVALID",
                        "候选口径参数必须是标量值。", false);
            }
            params.put(entry.getKey(), value);
        }
        String statStart = start.format(SQL_TIME);
        String statEnd = end.format(SQL_TIME);
        String sourceId;
        try {
            sourceId = sourceId(context);
        } catch (DatabaseSourceException exception) {
            return failure("forbidden", exception.code(), exception.getMessage(), false);
        }
        Map<String, Object> diagnosticExecution = new LinkedHashMap<>();
        diagnosticExecution.put("profileId", rule.get("profileId"));
        diagnosticExecution.put("overviewStaticRuntime", overviewStaticRuntime);
        if (diagnosticProfileId != null) {
            diagnosticExecution.put("diagnosticProfileId", diagnosticProfileId);
            diagnosticExecution.put("fieldRoleOverrides", fieldRoleOverrides);
        }
        Map<String, Object> snapshot = contextSnapshot(
                rule, mapping, params, statStart, statEnd, sourceId, diagnosticExecution);
        String digest = digest(snapshot);
        String sqlId = id("SQL_");
        Instant now = Instant.now();
        PreparedSqlObject sqlObject = new PreparedSqlObject(
                sqlId, context.agentContext().hospitalId(), context.agentContext().userId(),
                context.agentContext().sessionId(), input.ruleId(), text(mapping.get("dialect")), sql,
                params, statStart, statEnd, snapshot, digest, "validated",
                validation.message(),
                now, now.plus(SQL_TTL), sourceId);
        try {
            objects.save(sqlObject);
        } catch (RuntimeException exception) {
            return failure("error", "SQL_OBJECT_SAVE_FAILED", "SQL 对象保存失败，请重新准备。", false);
        }

        state.currentRuleId(input.ruleId());
        if (!state.validatedSqlIds().contains(sqlId)) {
            state.validatedSqlIds().add(sqlId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sqlId", sqlId);
        data.put("ruleId", input.ruleId());
        data.put("profileId", rule.get("profileId"));
        data.put("hospitalId", context.agentContext().hospitalId());
        data.put("sourceRole", sourceRole(sourceId));
        data.put("dbSourceId", sourceId);
        data.put("contextDigest", digest);
        data.put("dialect", sqlObject.dialect());
        data.put("validationStatus",
                overviewStaticRuntime ? "overview_static_validated" : "validated");
        data.put("sqlPreview", sql);
        Map<String, Object> displayParameters = new LinkedHashMap<>(params);
        displayParameters.put("hospital_id", context.agentContext().hospitalId());
        displayParameters.put("start_time", statStart);
        displayParameters.put("end_time", statEnd);
        data.put("parameters", displayParameters);
        data.put("statStart", statStart);
        data.put("statEnd", statEnd);
        data.put("sqlBundle", Map.of(
                "releaseId", text(rule.get("knowledgeReleaseId")),
                "ruleId", input.ruleId(),
                "profileId", text(rule.get("profileId")),
                "sqlHashes", objectMap(objectMap(snapshot.get("effectiveRule"))
                        .get("sqlBundleHashes"))));
        data.put("expiresAt", sqlObject.expiresAt().toString());
        if (diagnosticProfileId != null) {
            data.put("diagnosticProfileId", diagnosticProfileId);
        }
        return ToolResult.success(
                "SQL_OBJECT_PREPARED", "SQL 已完成确定性生成和只读安全校验，可进行受控试运行。", data);
    }

    /**
     * 为尚未完成医院执行契约的 Profile 展示知识库 SQL 参考稿。
     *
     * <p>该方法只做确定性模板渲染、已知无语义模板错误修复和只读静态校验，
     * 不创建 SQL 对象、不登记 validatedSqlId，也不访问 DBHub。因此返回值只能用于
     * 人工核对，后续试运行工具无法消费它。</p>
     */
    private ToolResult prepareReferenceOnly(
            PrepareInput input,
            LocalDateTime start,
            LocalDateTime end,
            Map<String, Object> rule,
            ToolExecutionContext context) {
        String template = text(rule.get("standardSql"));
        if (template.isBlank()) {
            return failure("unavailable", "SQL_TEMPLATE_UNAVAILABLE",
                    "当前口径没有可展示的知识库 SQL 模板。", false);
        }
        Map<String, Object> mapping = withExecutionDefaults(rules.fieldMapping(
                input.ruleId(), context.agentContext().hospitalId(), null));
        String sql;
        try {
            sql = normalizeKnownSqlArtifacts(renderer.render(
                    template, objectMap(mapping.get("fields")), text(mapping.get("mainTable"))));
        } catch (RuntimeException exception) {
            return failure("validation_failed", "SQL_REFERENCE_RENDER_FAILED",
                    "知识库 SQL 仍包含无法解析的模板字段，暂不能安全展示。", false);
        }
        ReadOnlySqlValidator.ValidationResult validation = validator.validateReadOnly(sql);
        if (!validation.ok()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ruleId", input.ruleId());
            data.put("profileId", rule.get("profileId"));
            data.put("executionStatus", rule.get("executionStatus"));
            data.put("executionBlockers", rule.get("executionBlockers"));
            data.put("staticValidationMessage", validation.message());
            return failure("validation_failed", "SQL_REFERENCE_VALIDATION_FAILED",
                    "知识库 SQL 未通过只读静态检查，已阻止展示。", false, data);
        }

        String statStart = start.format(SQL_TIME);
        String statEnd = end.format(SQL_TIME);
        Map<String, Object> displayParameters = new LinkedHashMap<>(
                objectMap(rule.get("effectiveParams")));
        displayParameters.put("hospital_id", context.agentContext().hospitalId());
        displayParameters.put("start_time", statStart);
        displayParameters.put("end_time", statEnd);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", input.ruleId());
        data.put("profileId", rule.get("profileId"));
        data.put("executionStatus", rule.get("executionStatus"));
        data.put("executionBlockers", rule.get("executionBlockers"));
        data.put("referenceOnly", true);
        data.put("validationStatus", "static_validated");
        data.put("sqlPreview", sql);
        data.put("parameters", displayParameters);
        data.put("statStart", statStart);
        data.put("statEnd", statEnd);
        return ToolResult.success(
                "SQL_REFERENCE_PREPARED",
                "已读取并完成知识库概览 SQL 的只读静态检查；本次仅展示，不执行数据库。",
                data);
    }

    /**
     * 只修复知识来源中已经登记、且不改变业务语义的模板残留。
     *
     * <p>不得在这里改表、字段、JOIN、过滤条件、阈值、聚合或时间边界。遇到其他
     * 未知问题必须由发布门禁阻断，不能由运行时代码或模型猜测修复。</p>
     */
    private static String normalizeKnownSqlArtifacts(String sql) {
        String normalized = sql
                .replace("#{NOLOCK}", "WITH (NOLOCK)")
                .replace("\u0000", "");
        /*
         * 原始 Excel 中的布局说明占据了 WHERE 后的占位条件。两种已登记形式分别为
         * “注释下一行自带 AND”和“注释下一行直接写谓词”。先规范化前者，再给后者
         * 补 AND，避免生成 `WHERE 1=1 event.xxx`，且不改变任何业务筛选条件。
         */
        normalized = normalized.replaceAll(
                "(?m)--布局组件设置提升效率\\s*\\R\\s*(?i:AND)\\b",
                "1=1 AND");
        return normalized.replace("--布局组件设置提升效率", "1=1 AND");
    }

    public ToolResult trial(TrialInput input, ToolExecutionContext context) {
        AgentRunState state = context.runState();
        if (!state.validatedSqlIds().contains(input.sqlId())) {
            return failure("unavailable", "SQL_OBJECT_NOT_ACTIVE", "该 SQL 对象不在当前已验证状态中，请重新准备。", false);
        }
        PreparedSqlObject sql;
        try {
            sql = objects.loadForExecution(input.sqlId(), context.agentContext(), Instant.now());
        } catch (SqlObjectAccessException exception) {
            if (Set.of("SQL_OBJECT_NOT_FOUND", "SQL_OBJECT_EXPIRED", "SQL_OBJECT_NOT_VALIDATED", "SQL_OBJECT_CORRUPTED")
                    .contains(exception.code())) {
                state.validatedSqlIds().remove(input.sqlId());
            }
            boolean forbidden = exception.code().contains("MISMATCH");
            return failure(forbidden ? "forbidden" : "unavailable", exception.code(), exception.getMessage(), false);
        }

        Map<String, Object> storedExecution =
                objectMap(sql.contextSnapshot().get("executionContext"));
        String profileId = text(storedExecution.get("profileId"));
        Map<String, Object> currentRule = rules.effectiveRule(
                sql.ruleId(), context.agentContext().hospitalId(), profileId);
        Map<String, Object> currentMapping = withExecutionDefaults(
                rules.fieldMapping(sql.ruleId(), context.agentContext().hospitalId(), profileId));
        try {
            currentMapping = applyDiagnosticFieldRoleOverrides(
                    currentMapping,
                    objectMap(storedExecution.get("fieldRoleOverrides")));
        } catch (IllegalArgumentException exception) {
            return failure("validation_failed", "SQL_CONTEXT_STALE",
                    "候选口径字段角色已失效，请重新准备 SQL 后再试运行。", false);
        }
        boolean overviewStaticRuntime =
                Boolean.TRUE.equals(storedExecution.get("overviewStaticRuntime"));
        Inspection inspection = inspection(currentRule, currentMapping);
        if (!overviewStaticRuntime && !inspection.ready()) {
            return failure("validation_failed", "SQL_CONTEXT_STALE",
                    "医院字段或元数据已变化，请重新准备 SQL 后再试运行。", false);
        }
        String currentDigest = digest(contextSnapshot(
                currentRule, currentMapping, sql.params(), sql.statStart(), sql.statEnd(),
                sql.dbSourceId(), storedExecution));
        if (!currentDigest.equals(sql.contextDigest())) {
            return failure("validation_failed", "SQL_CONTEXT_STALE",
                    "指标规则或字段映射已变化，请重新准备 SQL 后再试运行。", false);
        }
        ReadOnlySqlValidator.ValidationResult revalidation = overviewStaticRuntime
                ? validator.validateReadOnly(sql.sqlText())
                : validator.validate(sql.sqlText(), text(currentMapping.get("mainTable")));
        if (!revalidation.ok()) {
            return failure("validation_failed", "SQL_REVALIDATION_FAILED", "SQL 在试运行前未通过二次只读安全校验。", false);
        }
        /*
         * 普通指标计算固定走双库 Workflow。抽取开关只决定 Workflow 内部是否先调用
         * 抽取网关，不能再让 disabled 模式退回旧的业务库单库查询。
         */
        boolean dualExecution =
                dualDatabaseWorkflow != null && dualDatabaseWorkflow.enabled();
        if ("win60_qa_991827".equalsIgnoreCase(sql.dbSourceId())) {
            return failure("unavailable", "DB_SOURCE_RETIRED",
                    "该 SQL 对象引用的数据库已经退役，不能重新执行；请重新发起指标计算。",
                    false);
        }
        if (!dualExecution && sql.dbSourceId() != null && !sql.dbSourceId().isBlank()
                && !sql.dbSourceId().equals(businessQuery.sourceId())) {
            return failure("error", "TRIAL_SOURCE_MISMATCH", "试运行数据源与 SQL 对象不一致，结果已拒绝。", false);
        }

        Map<String, Object> bound = new LinkedHashMap<>(sql.params());
        bound.put("hospital_id", context.agentContext().hospitalId());
        bound.put("start_time", sql.statStart());
        bound.put("end_time", sql.statEnd());
        addTemplateParameterAliases(bound);
        String executable;
        try {
            executable = binder.bind(sql.sqlText(), bound);
        } catch (RuntimeException exception) {
            return failure("validation_failed", "SQL_PARAMETER_MISSING", "SQL 运行参数不完整，请重新准备。", false);
        }

        if (dualExecution) {
            return dualDatabaseWorkflow.execute(sql, currentRule, executable, bound, context);
        }

        String runId = id("RUN_");
        long started = System.nanoTime();
        try {
            List<Map<String, Object>> rows;
            try {
                rows = businessQuery.execute(executable);
            } catch (DbHubMcpException exception) {
                if (!transientConnectionFailure(exception.getMessage())) {
                    throw exception;
                }
                rows = businessQuery.execute(executable);
            }
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            Map<String, Object> first = rows.isEmpty() ? Map.of() : rows.get(0);
            Map<String, Object> resultMapping = objectMap(currentRule.get("resultMapping"));
            String resultColumn = first(
                    text(resultMapping.get("index_value")), "index_value");
            String numeratorColumn = first(
                    text(resultMapping.get("numerator_count")), "numerator_count");
            String denominatorColumn = first(
                    text(resultMapping.get("denominator_count")), "denominator_count");
            Number resultValue = number(value(first, resultColumn));
            Long numerator = longValue(value(first, numeratorColumn));
            Long denominator = longValue(value(first, denominatorColumn));
            if (denominator == null) {
                denominator = longValue(value(first, "sample_count"));
            }
            Number verifiedValue = verifiedPercentage(resultValue, numerator, denominator);
            if (verifiedValue == null && resultValue != null && numerator != null && denominator != null) {
                return failure("validation_failed", "NUMERIC_RESULT_INCONSISTENT",
                        "SQL返回指标值与分子分母复算结果不一致，本次结果已拒绝。", false);
            }
            if (verifiedValue != null) {
                resultValue = verifiedValue;
            }
            String status = resultValue == null ? "empty" : "success";
            Map<String, Object> runContext = new LinkedHashMap<>(sql.contextSnapshot());
            objects.saveRun(runId, sql, status, resultValue, numerator, denominator, "", durationMs,
                    context.agentContext().userId(), runContext);
            state.lastRunId(runId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sqlId", sql.sqlId());
            data.put("runId", runId);
            data.put("status", status);
            data.put("resultValue", resultValue);
            data.put("numeratorCount", numerator);
            data.put("denominatorCount", denominator);
            data.put("noSample", denominator != null && denominator == 0);
            data.put("durationMs", durationMs);
            data.put("source", businessQuery.sourceId());
            data.put("hospital_id", context.agentContext().hospitalId());
            data.put("sourceRole", sourceRole(sql.dbSourceId()));
            data.put("dbSourceId", sql.dbSourceId());
            data.put("ruleId", sql.ruleId());
            data.put("profileId", profileId);
            data.put("contextDigest", sql.contextDigest());
            data.put("statStart", sql.statStart());
            data.put("statEnd", sql.statEnd());
            return ToolResult.success("TRIAL_RUN_COMPLETED",
                    "success".equals(status) ? "只读试运行完成，已获得聚合结果。"
                            : "只读试运行完成，当前统计区间没有可用样本。",
                    data);
        } catch (RuntimeException exception) {
            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            try {
                objects.saveRun(runId, sql, "failed", null, null, null, "DBHub query failed", durationMs,
                        context.agentContext().userId(), sql.contextSnapshot());
            } catch (RuntimeException ignored) {
                // 原始 DBHub 错误优先，日志失败不能泄漏内部连接信息。
            }
            return failure("error", "TRIAL_RUN_FAILED", "只读试运行失败，未获得可用聚合结果。", true);
        }
    }

    private Inspection inspection(Map<String, Object> rule, Map<String, Object> mapping) {
        Set<String> required = new LinkedHashSet<>();
        Map<String, Object> businessFields = objectMap(
                objectMap(rule.get("fieldContract")).get("business_fields"));
        required.addAll(businessFields.keySet());
        /*
         * 新版知识发布流程会在目标医院同时完成 business/real 两个数据源的元数据和
         * 编译验证，并把结果固化进不可变 Profile。该发布契约比本机 SQLite 中可能
         * 尚未同步的元数据缓存更权威；只有发布契约不完整时，才要求缓存中每个字段
         * 都存在实际类型。字段映射缺失、未确认、类型冲突和关联缺失仍然始终阻断。
         */
        boolean publishedDualContractVerified = publishedDualContractVerified(rule);
        Set<String> mapped = new LinkedHashSet<>(objectMap(mapping.get("fields")).keySet());
        List<String> missing = required.stream().filter(value -> !mapped.contains(value)).sorted().toList();
        List<String> unconfirmed = listOfMaps(mapping.get("items")).stream()
                .filter(item -> !"confirmed".equals(text(item.get("status"))))
                .map(item -> text(item.get("businessField"))).filter(value -> !value.isBlank()).distinct().sorted().toList();
        List<String> missingColumns = new ArrayList<>();
        List<String> typeMismatches = new ArrayList<>();
        for (Map<String, Object> item : listOfMaps(mapping.get("metadataItems"))) {
            String businessField = text(item.get("businessField"));
            if (!required.contains(businessField)) continue;
            String mappedColumn = text(item.get("tableName")) + "." + text(item.get("columnName"));
            String actual = text(item.get("metadataDataType")).toLowerCase(Locale.ROOT);
            if (actual.isBlank()) {
                if (!publishedDualContractVerified) {
                    missingColumns.add(mappedColumn);
                }
                continue;
            }
            String expected = text(objectMap(businessFields.get(businessField)).get("type"))
                    .toLowerCase(Locale.ROOT);
            if (!typesCompatible(expected, actual)) {
                typeMismatches.add(businessField + "：期望 " + expected + "，实际 " + actual + "（" + mappedColumn + "）");
            }
        }
        Set<String> physicalTables = new LinkedHashSet<>();
        for (String value : objectMap(mapping.get("fields")).values().stream().map(String::valueOf).toList()) {
            String[] parts = value.split("\\.");
            if (parts.length >= 2) physicalTables.add(parts[parts.length - 2]);
        }
        String mainTable = text(mapping.get("mainTable"));
        List<String> missingRelations = new ArrayList<>();
        List<Map<String, Object>> relations = listOfMaps(mapping.get("relations"));
        for (String other : physicalTables) {
            if (other.equals(mainTable)) continue;
            boolean found = relations.stream().anyMatch(relation ->
                    (mainTable.equals(text(relation.get("left_table"))) && other.equals(text(relation.get("right_table"))))
                            || (mainTable.equals(text(relation.get("right_table"))) && other.equals(text(relation.get("left_table")))));
            if (!found) missingRelations.add(mainTable + " -> " + other);
        }
        boolean ready = "confirmed".equals(mapping.get("status"))
                && missing.isEmpty() && unconfirmed.isEmpty()
                && (publishedDualContractVerified || missingColumns.isEmpty())
                && typeMismatches.isEmpty() && missingRelations.isEmpty();
        return new Inspection(ready, mapped.stream().sorted().toList(), required.stream().sorted().toList(),
                missing, unconfirmed, missingColumns.stream().sorted().toList(),
                typeMismatches.stream().sorted().toList(), missingRelations.stream().sorted().toList());
    }

    private static boolean publishedDualContractVerified(Map<String, Object> rule) {
        Map<String, Object> contract = objectMap(rule.get("dualDatabaseContract"));
        if (!Boolean.TRUE.equals(contract.get("schema_compatible"))) {
            return false;
        }
        Map<String, Object> sourceVerification = objectMap(contract.get("source_verification"));
        for (String role : List.of("business", "real")) {
            Map<String, Object> verification = objectMap(sourceVerification.get(role));
            if (!"validated".equalsIgnoreCase(text(verification.get("metadata_status")))
                    || !"validated".equalsIgnoreCase(text(verification.get("compile_status")))) {
                return false;
            }
        }
        return true;
    }

    private static boolean typesCompatible(String expected, String actual) {
        if (expected.isBlank() || actual.isBlank()) return true;
        Map<String, Set<String>> groups = Map.of(
                "string", Set.of("char", "varchar", "text", "tinytext", "mediumtext", "longtext", "nvarchar", "nchar"),
                "datetime", Set.of("date", "datetime", "datetime2", "timestamp", "smalldatetime"),
                "integer", Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint"),
                "numeric", Set.of("decimal", "numeric", "float", "double", "real", "money", "smallmoney"),
                "boolean", Set.of("bool", "boolean", "tinyint", "bit"),
                "code", Set.of("char", "varchar", "nvarchar", "text", "tinyint", "smallint", "mediumint", "int",
                        "integer", "bigint", "decimal", "numeric"));
        return groups.getOrDefault(expected, Set.of(expected)).contains(actual);
    }

    private Map<String, Object> contextSnapshot(
            Map<String, Object> rule,
            Map<String, Object> mapping,
            Map<String, Object> params,
            String start,
            String end,
            String sourceId) {
        return contextSnapshot(rule, mapping, params, start, end, sourceId, Map.of());
    }

    private Map<String, Object> contextSnapshot(
            Map<String, Object> rule,
            Map<String, Object> mapping,
            Map<String, Object> params,
            String start,
            String end,
            String sourceId,
        Map<String, Object> executionContext) {
        Map<String, Object> ruleSnapshot = new LinkedHashMap<>(rule);
        Map<String, String> sqlHashes = new LinkedHashMap<>();
        for (String key : List.of(
                "standardSql",
                "sourceExtractSql",
                "departmentDetailSql",
                "patientDetailSql")) {
            String value = text(ruleSnapshot.remove(key));
            if (!value.isBlank()) {
                sqlHashes.put(key, sha256(value));
            }
        }
        ruleSnapshot.put("sqlBundleHashes", sqlHashes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("effectiveRule", ruleSnapshot);
        result.put("fieldMapping", mapping);
        result.put("executionContext", executionContext == null
                ? Map.of() : Map.copyOf(executionContext));
        result.put("params", params);
        result.put("statStart", start);
        result.put("statEnd", end);
        result.put("sourceRole", sourceRole(sourceId));
        result.put("dbSourceId", sourceId);
        return result;
    }

    /**
     * 将持久化的数据源编号转换为稳定角色。SQL 对象仍保留 source_id 以兼容旧表结构，
     * 同时在上下文快照中显式保存角色，后续 Evidence、Trace 和明细不会仅凭数据库名称猜测。
     */
    private String sourceRole(String sourceId) {
        if (businessQuery instanceof IndicatorDatabaseQueryClient roleClient
                && sourceId != null
                && sourceId.equalsIgnoreCase(roleClient.sourceId(DatabaseRole.REAL))) {
            return DatabaseRole.REAL.value();
        }
        if (sourceId != null && sourceId.equalsIgnoreCase(businessQuery.sourceId())) {
            return DatabaseRole.BUSINESS.value();
        }
        return "retired_or_invalid";
    }

    private Map<String, Object> withExecutionDefaults(Map<String, Object> raw) {
        Map<String, Object> mapping = deepMap(raw);
        Map<String, Object> fields = objectMap(mapping.get("fields"));
        String admit = text(fields.get("admit_time"));
        if (!admit.isBlank()) {
            fields.putIfAbsent("baseline_admit_time", admit);
            fields.putIfAbsent("period_time", admit);
        }
        mapping.put("fields", fields);
        return mapping;
    }

    private Map<String, Object> applyDiagnosticFieldRoleOverrides(
            Map<String, Object> raw,
            Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return raw;
        }
        Map<String, Object> mapping = deepMap(raw);
        Map<String, Object> fields = objectMap(mapping.get("fields"));
        Set<String> allowedTargets = Set.of("period_time", "admit_time");
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String targetRole = entry.getKey();
            String sourceRole = text(entry.getValue());
            if (!allowedTargets.contains(targetRole)
                    || sourceRole.isBlank()
                    || !fields.containsKey(sourceRole)
                    || text(fields.get(sourceRole)).isBlank()) {
                throw new IllegalArgumentException(
                        "候选口径字段角色必须引用已确认的统计时间或耗时起点字段。");
            }
            fields.put(targetRole, fields.get(sourceRole));
        }
        mapping.put("fields", fields);
        return mapping;
    }

    private String digest(Object value) {
        try {
            return sha256(objectMapper.writeValueAsString(canonical(value)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 SQL 上下文指纹", exception);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private Map<String, Object> deepMap(Map<String, Object> value) {
        return objectMapper.convertValue(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(objectMap(item));
        }
        return result;
    }

    private static List<Map<String, Object>> safeItems(List<Map<String, Object>> items) {
        return allow(items, Set.of("businessField", "tableName", "columnName", "dataType", "status"));
    }

    private static List<Map<String, Object>> safeRelations(List<Map<String, Object>> items) {
        return allow(items, Set.of("left_table", "left_column", "right_table", "right_column",
                "join_type", "relation_source", "status"));
    }

    private static List<Map<String, Object>> allow(List<Map<String, Object>> items, Set<String> keys) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> safe = new LinkedHashMap<>();
            keys.forEach(key -> {
                if (item.containsKey(key)) safe.put(key, item.get(key));
            });
            result.add(safe);
        }
        return result;
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        if (value == null || value.toString().isBlank()) return null;
        return Double.parseDouble(value.toString());
    }

    private static Long longValue(Object value) {
        Number number = number(value);
        return number == null ? null : number.longValue();
    }

    private static Number verifiedPercentage(Number returned, Long numerator, Long denominator) {
        if (numerator == null || denominator == null) {
            return returned;
        }
        double calculated = denominator == 0
                ? 0.0
                : numerator.doubleValue() * 100.0 / denominator.doubleValue();
        if (returned != null && Math.abs(returned.doubleValue() - calculated) > 0.011) {
            return null;
        }
        // 对外契约历史上使用 JSON number（Double）。这里先按两位小数复算，再返回
        // Double，避免 25.00 与 25.0 因 Java 数值类型不同导致 Evidence/回答契约误判。
        return Math.round(calculated * 100.0) / 100.0;
    }

    private static boolean transientConnectionFailure(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return List.of("socket hang up", "connection lost", "connection reset", "connection aborted", "连接中断", "连接已断开")
                .stream().anyMatch(value::contains);
    }

    private String sourceId(ToolExecutionContext context) {
        String requested = context.agentContext().dbSourceId();
        if (requested == null || requested.isBlank()
                || requested.equalsIgnoreCase(businessQuery.sourceId())
                || requested.equalsIgnoreCase(DbHubProperties.BUSINESS_SOURCE_ID)) {
            return businessQuery.sourceId();
        }
        if ("win60_qa_991827".equalsIgnoreCase(requested)) {
            throw DatabaseSourceException.retired();
        }
        /*
         * 真实库只允许由双库 Workflow 在服务端按 DatabaseRole.REAL 选择。普通聊天
         * 请求不能直接指定真实库或任意 DBHub source-id，防止绕过抽取和双库核对。
         */
        throw DatabaseSourceException.invalid();
    }

    /**
     * 兼容知识库原始模板中的参数名，不改写SQL正文。别名值只能来自服务端已经
     * 确认的统计周期和医院范围，模型和浏览器不能借此注入额外参数。
     */
    private static void addTemplateParameterAliases(Map<String, Object> parameters) {
        Object start = parameters.get("start_time");
        Object end = parameters.get("end_time");
        Object hospitalScope = parameters.get("hospital_scope_value");
        if (start != null) {
            parameters.putIfAbsent("startTime", start);
            parameters.putIfAbsent("marptBeginAt", start);
        }
        if (end != null) {
            parameters.putIfAbsent("endTime", end);
            parameters.putIfAbsent("marptEndAt", end);
        }
        if (hospitalScope != null) {
            parameters.putIfAbsent("hospital_soid", hospitalScope);
        } else if (parameters.get("hospital_soid") != null) {
            parameters.put("hospital_scope_value", parameters.get("hospital_soid"));
        }
    }

    private static ToolResult failure(String status, String code, String summary, boolean retryable) {
        return failure(status, code, summary, retryable, Map.of());
    }

    private static ToolResult failure(
            String status, String code, String summary, boolean retryable, Map<String, Object> data) {
        return new ToolResult(false, status, code, summary, data, retryable, false, List.of());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 SHA-256", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    public record InspectInput(String ruleId) {
        public InspectInput {
            ruleId = ruleId == null ? "" : ruleId.strip();
            if (ruleId.isEmpty()) throw new IllegalArgumentException("规则编号不能为空");
        }
    }

    public record PrepareInput(
            String ruleId,
            String statStartTime,
            String statEndTime,
            String profileId) {
        public PrepareInput(String ruleId, String statStartTime, String statEndTime) {
            this(ruleId, statStartTime, statEndTime, null);
        }

        public PrepareInput {
            ruleId = ruleId == null ? "" : ruleId.strip();
            statStartTime = statStartTime == null ? "" : statStartTime.strip();
            statEndTime = statEndTime == null ? "" : statEndTime.strip();
            profileId = profileId == null || profileId.isBlank()
                    ? null : profileId.strip();
            if (ruleId.isEmpty() || statStartTime.isEmpty() || statEndTime.isEmpty()) {
                throw new IllegalArgumentException("SQL 准备参数不完整");
            }
        }
    }

    public record TrialInput(String sqlId) {
        public TrialInput {
            sqlId = sqlId == null ? "" : sqlId.strip();
            if (!sqlId.matches("SQL_[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("SQL 对象编号无效");
        }
    }

    private record Inspection(
            boolean ready,
            List<String> mappedFields,
            List<String> requiredFields,
            List<String> missingMappings,
            List<String> unconfirmedMappings,
            List<String> missingColumns,
            List<String> typeMismatches,
            List<String> missingRelations) {}
}

package com.hospital.wikiagent.agent.diagnosis;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cn.hutool.core.lang.Snowflake;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.EntitySqlDialectResolver;
import com.hospital.wikiagent.agent.mras.MrasParameterMapper;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.mras.MrasTemplateRenderer;
import com.hospital.wikiagent.enums.DbTypeEnum;
import com.hospital.wikiagent.utils.SqlAliasConverter;
import com.hospital.wikiagent.utils.SqlDialectsParseUtil;

/**
 * 在独立 SQL Server 影子表中验证候选抽取或统计 SQL。组件复制正式目标表结构、
 * 写入候选结果并重写概览查询，比较新旧分子分母、案例记录和重复键；影子表名
 * 绑定任务编号，整个过程不清空、不覆盖正式中间表，也不改变正式指标卡片。
 */
@Component
@ConditionalOnExpression("${wiki.sqlserver.enabled:true} and ${wiki.bizdb.enabled:true}")
public class DiagnosisShadowRunner {

    private static final List<String> DEFAULT_BUSINESS_KEYS = List.of(
            "ENCOUNTER_ID", "BIZ_ID", "EVENT_ID", "ORDER_ID", "CLI_ORDER_ID", "SURGERY_ID");
    private static final Set<String> AUDIT_TIME_COLUMNS =
            Set.of("CREATED_AT", "MODIFIED_AT", "UPDATED_AT");
    private final JdbcTemplate business;
    private final JdbcTemplate real;
    private final EntityPageParser entities;
    private final MrasParameterMapper parameterMapper;
    private final MrasTemplateRenderer renderer;
    private final Snowflake snowflake = new Snowflake(1, 1);
    private DiagnosisShadowDiffStore diffStore;
    private EntitySqlDialectResolver sqlDialects;
    private SqlDialectsParseUtil dialectsParser;

    public DiagnosisShadowRunner(
            @Qualifier("bizDbJdbcTemplate") JdbcTemplate business,
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate real,
            EntityPageParser entities,
            MrasParameterMapper parameterMapper,
            MrasTemplateRenderer renderer) {
        this.business = business;
        this.real = real;
        this.entities = entities;
        this.parameterMapper = parameterMapper;
        this.renderer = renderer;
    }

    @Autowired(required = false)
    void setDiffStore(DiagnosisShadowDiffStore value) {
        this.diffStore = value;
    }

    @Autowired(required = false)
    void setSqlDialects(EntitySqlDialectResolver value) {
        this.sqlDialects = value;
    }

    @Autowired(required = false)
    void setDialectsParser(SqlDialectsParseUtil value) {
        this.dialectsParser = value;
    }

    public Map<String, Object> run(
            DiagnosisCaseSnapshot snapshot,
            LocalDateTime start,
            LocalDateTime end) {
        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        if (entity == null) throw new IllegalStateException("当前生效口径不存在");
        if (!snapshot.knowledgeReleaseId().equals(entities.knowledgeReleaseId(snapshot.hospitalId()))) {
            throw new IllegalStateException("试跑前正式知识库版本已变化，请重新开始排查");
        }
        String layer = text(snapshot.candidateSql().get("layer"));
        String candidate = text(snapshot.candidateSql().get("sql"));
        String nodeId = text(snapshot.candidateSql().get("nodeId"));
        // Match the canonical SQL snapshot used when the candidate was built.
        // Some historical Markdown pages wrap the full query in quotes, which
        // are removed by the formal execution path and must not invalidate a
        // perfectly unchanged candidate baseline.
        String original = MrasSqlExecutionService.stripLeadingTrailingQuotes(
                currentNodeSql(entity, layer, nodeId));
        if (!sha256(original).equals(text(snapshot.candidateSql().get("originalSqlHash")))) {
            throw new IllegalStateException("候选 SQL基线哈希与当前生效 SQL不一致");
        }
        Map<String, Object> params = new LinkedHashMap<>(parameterMapper.mapTimeOnly(start, end));
        // 影子抽取必须与正式批量计算采用相同的出院窗口分支；否则
        // #EQUALS{:syncType; outHosp; ...} 会被删除，试跑范围会悄悄扩大到全量数据。
        params.put("syncType", "outHosp");
        String trialSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String shadow = "#DIAG_" + safe(snapshot.caseId(), 16) + "_" + trialSuffix;
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trialId", "TRIAL_" + snapshot.caseId() + "_" + trialSuffix);
        result.put("executedSqlHash", text(snapshot.candidateSql().get("candidateSqlHash")));
        result.put("knowledgeReleaseId", snapshot.knowledgeReleaseId());
        result.put("statStart", start.toString());
        result.put("statEnd", end.toString());
        result.put("layer", layer);
        result.put("attempted", true);
        result.put("completed", false);
        result.put("shadowTable", "SOURCE_EXTRACT".equals(layer) ? shadow : "不需要影子表");
        result.put("shadowStorage", "SOURCE_EXTRACT".equals(layer)
                ? "SQL_SERVER_SESSION_TEMP" : "READ_ONLY_QUERY");
        if ("SOURCE_EXTRACT".equals(layer)) {
            if (entity.targetTable() == null || entity.targetTable().isBlank()) {
                throw new IllegalStateException("抽取 SQL候选缺少目标中间表");
            }
            real.execute((ConnectionCallback<Void>) connection -> {
                JdbcTemplate session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                try {
                    String executable = renderer.renderTemplate(candidate, params);
                    JdbcTemplate source = entity.sourceQueryFromReal() ? session : business;
                    executable = sourceExecutableSql(executable, entity.sourceQueryFromReal());
                    result.put("sourceDatabaseRole", entity.sourceQueryFromReal() ? "REAL" : "BUSINESS");
                    List<Map<String, Object>> rows = new NamedParameterJdbcTemplate(source)
                            .queryForList(executable, jdbcSourceParams(params));
                    Long formalRows = session.queryForObject(
                            "SELECT COUNT_BIG(1) FROM [dbo].[" + entity.targetTable() + "]", Long.class);
                    createShadow(session, entity.targetTable(), shadow);
                    int inserted = insertRows(session, entity.targetTable(), shadow, rows);
                    String oldOverview = renderExecutableOverview(entity.overviewSql(), params);
                    String newOverview = replaceTable(oldOverview, entity.targetTable(), shadow);
                    List<Map<String, Object>> oldResult = session.queryForList(oldOverview);
                    List<Map<String, Object>> newResult = session.queryForList(newOverview);
                    result.put("sourceRows", rows.size());
                    result.put("formalRows", formalRows == null ? 0L : formalRows);
                    result.put("shadowRows", inserted);
                    result.put("originalResult", oldResult);
                    result.put("candidateResult", newResult);
                    result.put("originalAttainment", attainmentMetadata(snapshot, oldResult));
                    result.put("candidateAttainment", attainmentMetadata(snapshot, newResult));
                    String comparisonKey = resolveTargetKey(
                            entity.targetTable(), text(snapshot.caseInput().get("recordField")));
                    List<Map<String, Object>> formalData = session.queryForList(
                            "SELECT * FROM [dbo].[" + entity.targetTable() + "]");
                    DiffComputation differences = recordSetDiff(
                            comparisonKey, formalData, rows, snapshot.caseInput());
                    result.put("recordSetDiff", differences.summary());
                    if (diffStore != null) {
                        diffStore.replace(snapshot.caseId(), text(result.get("trialId")), differences.entries());
                    }
                    result.put("rowCountReconciled", inserted == rows.size());
                    result.put("aggregateProduced", !newResult.isEmpty());
                    Map<String, Object> caseValidation = validateCaseChange(
                            entity.targetTable(), comparisonKey, snapshot, rows);
                    result.put("caseValidation", caseValidation);
                    result.put("caseChangedAsExpected", caseValidation.get("ok"));
                    result.put("duplicateCheck", duplicateCheck(
                            entity.targetTable(), comparisonKey, rows, snapshot.caseInput()));
                    result.put("passed", inserted == rows.size()
                            && !newResult.isEmpty()
                            && Boolean.TRUE.equals(result.get("caseChangedAsExpected"))
                            && Boolean.TRUE.equals(((Map<?, ?>) result.get("duplicateCheck")).get("ok")));
                    result.put("executionViews", executionViews(
                            session, entity, layer, candidate, shadow, params, snapshot.candidateSql()));
                    return null;
                } finally {
                    dropSessionShadowQuietly(session, shadow);
                }
            });
        } else if (List.of("OVERVIEW", "STATISTICS").contains(layer)) {
            String oldOverview = renderExecutableOverview(original, params);
            String newOverview = renderExecutableOverview(candidate, params);
            List<Map<String, Object>> originalResult = real.queryForList(oldOverview);
            result.put("originalResult", originalResult);
            List<Map<String, Object>> candidateResult = real.queryForList(newOverview);
            result.put("candidateResult", candidateResult);
            result.put("originalAttainment", attainmentMetadata(snapshot, originalResult));
            result.put("candidateAttainment", attainmentMetadata(snapshot, candidateResult));
            result.put("rowCountReconciled", true);
            result.put("caseChangedAsExpected", true);
            result.put("duplicateCheck", Map.of("ok", true, "message", "统计 SQL只读试跑不写入记录"));
            result.put("aggregateProduced", !candidateResult.isEmpty());
            result.put("passed", !candidateResult.isEmpty());
        } else {
            throw new IllegalStateException("不支持的候选 SQL层级: " + layer);
        }
        if (!result.containsKey("executionViews")) {
            result.put("executionViews", executionViews(
                    real, entity, layer, candidate, shadow, params, snapshot.candidateSql()));
        }
        result.put("completed", true);
        result.put("durationMs", System.currentTimeMillis() - started);
        result.put("knowledgeReleaseUnchanged", true);
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> attainmentMetadata(
            DiagnosisCaseSnapshot snapshot, List<Map<String, Object>> rows) {
        Map<String, Object> execution = snapshot.gateResults().stream()
                .filter(item -> intValue(item.get("gate")) == 2)
                .map(item -> map(map(item.get("facts")).get("executionEvidence")))
                .findFirst().orElse(Map.of());
        Object targetRaw = firstValue(rows, "TARGET_VALUE", "目标值");
        if (targetRaw == null) targetRaw = execution.get("targetValue");
        String direction = text(execution.get("targetDirection"));
        Object resultRaw = firstValue(rows, "RESULT_VALUE", "监测情况", "指标结果");
        BigDecimal actual = decimal(resultRaw);
        BigDecimal target = decimal(targetRaw);
        String label = outputAttainmentLabel(firstValue(
                rows, "是否达标", "达标状态", "对比结果", "STAND_FLAG"));
        if ("待判定".equals(label)) {
            label = outputAttainmentLabel(execution.get("qualifiedLabel"));
        }
        if (direction.isBlank() && actual != null && target != null
                && !"待判定".equals(label) && actual.compareTo(target) != 0) {
            boolean below = actual.compareTo(target) < 0;
            direction = "达标".equals(label)
                    ? (below ? "<" : ">=")
                    : (below ? ">=" : "<");
        }
        if (actual != null && target != null && !direction.isBlank()) {
            int compare = actual.compareTo(target);
            boolean met = switch (direction.toUpperCase(Locale.ROOT)) {
                case "GT", ">", "UP" -> compare > 0;
                case "GTE", ">=" -> compare >= 0;
                case "LT", "<", "DOWN" -> compare < 0;
                case "LTE", "<=" -> compare <= 0;
                default -> false;
            };
            label = met ? "达标" : "未达标";
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("targetValue", targetRaw == null ? "" : targetRaw);
        value.put("targetDirection", direction);
        value.put("attainmentLabel", label);
        value.put("unit", text(execution.get("unit")));
        return java.util.Collections.unmodifiableMap(value);
    }

    private static String outputAttainmentLabel(Object value) {
        String label = text(value).strip();
        if (label.contains("未达标") || "否".equals(label) || "98176".equals(label)) {
            return "未达标";
        }
        if (label.contains("达标") || "是".equals(label) || "98175".equals(label)) {
            return "达标";
        }
        return "待判定";
    }

    private static Object firstValue(
            List<Map<String, Object>> rows, String... candidates) {
        if (rows == null || rows.isEmpty()) return null;
        for (Map.Entry<String, Object> entry : rows.get(0).entrySet()) {
            String key = text(entry.getKey()).toUpperCase(Locale.ROOT);
            for (String candidate : candidates) {
                if (key.equals(candidate.toUpperCase(Locale.ROOT)) || key.contains(candidate)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        try {
            String text = text(value).replace("%", "").trim();
            return text.isBlank() ? null : new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> executionViews(
            JdbcTemplate jdbc,
            EntityPageData entity,
            String layer,
            String candidate,
            String shadow,
            Map<String, Object> params,
            Map<String, Object> candidateSnapshot) {
        String overview = "OVERVIEW".equals(layer) ? candidate : entity.overviewSql();
        String department = entity.deptStatSql();
        String patient = entity.patientDetailSql();
        Object rawChanges = candidateSnapshot.get("changes");
        if (rawChanges instanceof List<?> changes) {
            for (Object value : changes) {
                if (!(value instanceof Map<?, ?> change)) continue;
                String sqlKind = text(change.get("sqlKind"));
                String changeSql = text(change.get("candidateSql"));
                if ("OVERVIEW".equals(sqlKind)) overview = changeSql;
                if ("DEPARTMENT".equals(sqlKind)) department = changeSql;
                if ("PATIENT_DETAIL".equals(sqlKind)) patient = changeSql;
            }
        }
        if ("SOURCE_EXTRACT".equals(layer)) {
            overview = replaceTable(overview, entity.targetTable(), shadow);
            department = replaceTable(department, entity.targetTable(), shadow);
            patient = replaceTable(patient, entity.targetTable(), shadow);
        }
        Map<String, Object> views = new LinkedHashMap<>();
        views.put("overview", executionView(jdbc, "OVERVIEW", overview, params));
        views.put("department", executionView(jdbc, "DEPARTMENT", department, params));
        views.put("patient", executionView(jdbc, "PATIENT_DETAIL", patient, params));
        return java.util.Collections.unmodifiableMap(views);
    }

    private Map<String, Object> executionView(
            JdbcTemplate jdbc, String sqlKind, String sql, Map<String, Object> params) {
        if (sql == null || sql.isBlank()) {
            return Map.of("sqlKind", sqlKind, "available", false,
                    "message", "当前口径未登记该类 SQL");
        }
        String executable = renderExecutableOverview(sql, params);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sqlKind", sqlKind);
        view.put("available", true);
        view.put("sql", executable);
        try {
            List<Map<String, Object>> rows = jdbc.query(executable, resultSet -> {
                List<Map<String, Object>> limited = new java.util.ArrayList<>();
                java.sql.ResultSetMetaData metadata = resultSet.getMetaData();
                int columns = metadata.getColumnCount();
                while (resultSet.next() && limited.size() < 20) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= columns; index++) {
                        row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
                    }
                    limited.add(java.util.Collections.unmodifiableMap(row));
                }
                return List.copyOf(limited);
            });
            view.put("status", "SUCCESS");
            view.put("rows", rows);
            view.put("previewCount", rows.size());
        } catch (RuntimeException exception) {
            view.put("status", "FAILED");
            view.put("message", safeMessage(exception));
            view.put("rows", List.of());
            view.put("previewCount", 0);
        }
        return java.util.Collections.unmodifiableMap(view);
    }

    String renderExecutableOverview(String sql, Map<String, Object> params) {
        return renderer.render(MrasSqlExecutionService.stripLeadingTrailingQuotes(sql), params);
    }

    private void createShadow(JdbcTemplate jdbc, String target, String shadow) {
        assertSessionShadowName(shadow);
        jdbc.execute(sessionShadowCreateSql(target, shadow));
    }

    private int insertRows(
            JdbcTemplate jdbc, String target, String shadow, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return 0;
        assertSessionShadowName(shadow);
        List<ShadowColumn> targetColumns = jdbc.query(
                "SELECT [COLUMN_NAME],[DATA_TYPE],[IS_NULLABLE] FROM [INFORMATION_SCHEMA].[COLUMNS] "
                        + "WHERE [TABLE_SCHEMA]='dbo' AND [TABLE_NAME]=? ORDER BY [ORDINAL_POSITION]",
                (result, rowNum) -> new ShadowColumn(result.getString(1), result.getString(2),
                        "YES".equalsIgnoreCase(result.getString(3))), target);
        Map<String, String> sourceKeys = new LinkedHashMap<>();
        rows.get(0).keySet().forEach(key -> {
            sourceKeys.put(key.toUpperCase(Locale.ROOT), key);
            // 正式 SyncDataService 会把 camelCase 输出列转换成 UPPER_SNAKE_CASE；
            // 影子写入必须采用相同映射，否则 encounterId 等列不会进入目标表。
            sourceKeys.put(toUpperSnake(key), key);
        });
        String pkColumn = target.toUpperCase(Locale.ROOT).replaceFirst("_\\d+$", "") + "_ID";
        List<ShadowColumn> columns = targetColumns.stream()
                .filter(column -> column.name().equalsIgnoreCase(pkColumn)
                        || sourceKeys.containsKey(column.name().toUpperCase(Locale.ROOT))
                        || isAuditTimestampColumn(column.name(), column.type()))
                .toList();
        List<String> missingRequired = targetColumns.stream()
                .filter(column -> !column.nullable()
                        && !column.name().equalsIgnoreCase(pkColumn)
                        && !isAuditTimestampColumn(column.name(), column.type())
                        && !sourceKeys.containsKey(column.name().toUpperCase(Locale.ROOT)))
                .map(ShadowColumn::name).toList();
        if (!missingRequired.isEmpty()) {
            throw new IllegalStateException("候选抽取结果缺少目标表必填字段: "
                    + String.join("、", missingRequired));
        }
        if (columns.isEmpty()) throw new IllegalStateException("候选抽取结果与目标表没有同名字段");
        String sql = "INSERT INTO [" + shadow + "] ("
                + columns.stream().map(column -> "[" + column.name() + "]")
                        .collect(java.util.stream.Collectors.joining(","))
                + ") VALUES (" + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        int[][] counts = jdbc.batchUpdate(sql, rows, 200, (statement, row) -> {
            long generatedPk = snowflake.nextId();
            for (int index = 0; index < columns.size(); index++) {
                ShadowColumn column = columns.get(index);
                String key = sourceKeys.get(column.name().toUpperCase(Locale.ROOT));
                Object value = key == null ? null : row.get(key);
                if (column.name().equalsIgnoreCase(pkColumn)
                        && (value == null || text(value).isBlank())) {
                    value = generatedPk;
                }
                if (value == null && isAuditTimestampColumn(column.name(), column.type())) {
                    // 与正式 SyncDataService 保持一致：目标表的审计时间列可能是
                    // NOT NULL 且没有数据库默认值，源 SQL 不需要显式返回这些系统字段。
                    value = new java.sql.Timestamp(System.currentTimeMillis());
                }
                value = convertValueByType(value, column.type());
                if (value == null && !column.nullable()) {
                    throw new IllegalStateException("候选抽取结果的目标必填字段为空: " + column.name());
                }
                statement.setObject(index + 1, value);
            }
        });
        int total = 0;
        for (int[] batch : counts) {
            for (int count : batch) {
                if (count >= 0) total += count;
                else if (count == java.sql.Statement.SUCCESS_NO_INFO) total++;
            }
        }
        return total;
    }

    static boolean isAuditTimestampColumn(String column, String dataType) {
        return column != null && dataType != null
                && dataType.toLowerCase(Locale.ROOT).contains("date")
                && AUDIT_TIME_COLUMNS.contains(column.toUpperCase(Locale.ROOT));
    }

    private void dropSessionShadowQuietly(JdbcTemplate jdbc, String shadow) {
        try {
            assertSessionShadowName(shadow);
            jdbc.execute(sessionShadowDropSql(shadow));
        } catch (RuntimeException ignored) {
            // 关闭同一 JDBC 连接时 SQL Server 仍会自动释放本地临时表。
        }
    }

    static void assertSessionShadowName(String shadow) {
        if (shadow == null || !shadow.matches("^#DIAG_[A-Za-z0-9_]+$")) {
            throw new IllegalArgumentException("影子临时表名不合法");
        }
    }

    static String sessionShadowCreateSql(String target, String shadow) {
        assertSessionShadowName(shadow);
        if (target == null || !target.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("正式中间表名不合法");
        }
        return "SELECT TOP (0) * INTO [" + shadow + "] FROM [dbo].[" + target + "]";
    }

    static String sessionShadowDropSql(String shadow) {
        assertSessionShadowName(shadow);
        return "IF OBJECT_ID(N'tempdb.." + shadow + "', N'U') IS NOT NULL "
                + "DROP TABLE [" + shadow + "]";
    }

    static Object convertValueByType(Object value, String dataType) {
        if (value == null || dataType == null || value instanceof Number) return value;
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) return null;
            String type = dataType.toLowerCase(Locale.ROOT);
            if (type.contains("int") || type.contains("numeric")
                    || type.contains("decimal") || type.contains("float")
                    || type.contains("real") || type.contains("money")) {
                try {
                    return stringValue.contains(".")
                            ? new java.math.BigDecimal(stringValue) : Long.parseLong(stringValue);
                } catch (NumberFormatException ignored) {
                    return value;
                }
            }
        }
        return value;
    }

    private Map<String, Object> duplicateCheck(
            String targetTable, String targetKey, List<Map<String, Object>> rows,
            Map<String, Object> caseInput) {
        final String key = effectiveBusinessKey(caseInput, targetKey);
        if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || targetKey.isBlank()) {
            return Map.of("ok", false, "message", "未登记业务唯一键，无法证明没有新增重复记录");
        }
        Map<String, Integer> candidateCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String value = rowValue(row, key);
            if (!value.isBlank()) candidateCounts.merge(value, 1, Integer::sum);
        }
        Map<String, Integer> formalCounts = new LinkedHashMap<>();
        real.query("SELECT CONVERT(NVARCHAR(300), [" + targetKey + "]), COUNT_BIG(1) "
                        + "FROM [dbo].[" + targetTable + "] WHERE [" + targetKey
                        + "] IS NOT NULL GROUP BY [" + targetKey + "]",
                result -> {
                    formalCounts.put(result.getString(1), result.getInt(2));
                });
        List<String> duplicates = newDuplicateKeys(formalCounts, candidateCounts);
        return Map.of("ok", duplicates.isEmpty(), "uniqueKey", key,
                "duplicateKeys", duplicates, "duplicateCount", duplicates.size());
    }

    private DiffComputation recordSetDiff(
            String targetKey, List<Map<String, Object>> formalRows,
            List<Map<String, Object>> candidateRows, Map<String, Object> caseInput) {
        final String key = effectiveBusinessKey(caseInput, targetKey);
        if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || targetKey.isBlank()) {
            return new DiffComputation(
                    Map.of("status", "UNAVAILABLE", "reason", "未登记可比较的业务唯一键"), List.of());
        }
        try {
            Map<String, List<Map<String, Object>>> originalRows = groupRows(formalRows, targetKey, false);
            Map<String, List<Map<String, Object>>> candidateGrouped = groupRows(candidateRows, key, true);
            java.util.Set<String> original = new java.util.LinkedHashSet<>(originalRows.keySet());
            java.util.Set<String> candidate = new java.util.LinkedHashSet<>(candidateGrouped.keySet());
            List<String> added = candidate.stream().filter(value -> !original.contains(value))
                    .limit(100).toList();
            List<String> removed = original.stream().filter(value -> !candidate.contains(value))
                    .limit(100).toList();
            List<DiagnosisShadowDiffStore.DiffEntry> entries = new java.util.ArrayList<>();
            for (String value : candidate) {
                if (!original.contains(value)) {
                    entries.add(new DiagnosisShadowDiffStore.DiffEntry(
                            "ADDED", value, List.of(), candidateGrouped.get(value), List.of()));
                }
            }
            for (String value : original) {
                if (!candidate.contains(value)) {
                    entries.add(new DiagnosisShadowDiffStore.DiffEntry(
                            "REMOVED", value, originalRows.get(value), List.of(), List.of()));
                }
            }
            for (String value : original) {
                if (!candidate.contains(value)) continue;
                List<Map<String, Object>> before = comparableRows(originalRows.get(value), candidateGrouped.get(value));
                List<Map<String, Object>> after = comparableRows(candidateGrouped.get(value), originalRows.get(value));
                List<String> fields = changedFields(before, after);
                if (!fields.isEmpty() || originalRows.get(value).size() != candidateGrouped.get(value).size()) {
                    entries.add(new DiagnosisShadowDiffStore.DiffEntry(
                            "CHANGED", value, originalRows.get(value), candidateGrouped.get(value), fields));
                }
                if (candidateGrouped.get(value).size() > Math.max(1, originalRows.get(value).size())) {
                    entries.add(new DiagnosisShadowDiffStore.DiffEntry(
                            "DUPLICATE", value, originalRows.get(value), candidateGrouped.get(value), fields));
                }
            }
            Map<String, Object> summary = Map.of("status", "COMPLETED", "uniqueKey", key,
                    "originalCount", original.size(), "candidateCount", candidate.size(),
                    "addedCount", candidate.stream().filter(value -> !original.contains(value)).count(),
                    "removedCount", original.stream().filter(value -> !candidate.contains(value)).count(),
                    "changedCount", entries.stream().filter(item -> "CHANGED".equals(item.type())).count(),
                    "duplicateCount", entries.stream().filter(item -> "DUPLICATE".equals(item.type())).count(),
                    "addedSample", added, "removedSample", removed);
            return new DiffComputation(summary, List.copyOf(entries));
        } catch (RuntimeException exception) {
            return new DiffComputation(Map.of("status", "UNAVAILABLE", "uniqueKey", key,
                    "reason", safeMessage(exception)), List.of());
        }
    }

    private static Map<String, List<Map<String, Object>>> groupRows(
            List<Map<String, Object>> rows, String key, boolean source) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String value = source ? rowValue(row, key) : valueIgnoreCase(row, key);
            if (value.isBlank()) continue;
            result.computeIfAbsent(value, ignored -> new java.util.ArrayList<>()).add(normalizeRow(row));
        }
        result.replaceAll((ignored, value) -> List.copyOf(value));
        return result;
    }

    private static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> result = new java.util.TreeMap<>();
        row.forEach((field, value) -> result.put(toUpperSnake(field), value));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static List<Map<String, Object>> comparableRows(
            List<Map<String, Object>> rows, List<Map<String, Object>> other) {
        java.util.Set<String> fields = new java.util.LinkedHashSet<>();
        rows.forEach(row -> fields.addAll(row.keySet()));
        java.util.Set<String> otherFields = new java.util.LinkedHashSet<>();
        other.forEach(row -> otherFields.addAll(row.keySet()));
        fields.retainAll(otherFields);
        fields.removeIf(field -> field.endsWith("_ID") && field.startsWith("MRAS_BUSINESS_"));
        fields.remove("EXTRACT_AT");
        return rows.stream().map(row -> {
            Map<String, Object> value = new java.util.TreeMap<>();
            fields.forEach(field -> value.put(field, row.get(field)));
            return java.util.Collections.unmodifiableMap(value);
        }).sorted(java.util.Comparator.comparing(Object::toString)).toList();
    }

    private static List<String> changedFields(
            List<Map<String, Object>> before, List<Map<String, Object>> after) {
        java.util.Set<String> fields = new java.util.TreeSet<>();
        before.forEach(row -> fields.addAll(row.keySet()));
        after.forEach(row -> fields.addAll(row.keySet()));
        return fields.stream().filter(field -> !columnValues(before, field)
                .equals(columnValues(after, field))).toList();
    }

    private static List<String> columnValues(List<Map<String, Object>> rows, String field) {
        return rows.stream().map(row -> text(row.get(field))).sorted().toList();
    }

    private static String valueIgnoreCase(Map<String, Object> row, String field) {
        return row.entrySet().stream().filter(entry -> field.equalsIgnoreCase(entry.getKey()))
                .map(entry -> text(entry.getValue())).findFirst().orElse("");
    }

    private record DiffComputation(
            Map<String, Object> summary,
            List<DiagnosisShadowDiffStore.DiffEntry> entries) {}

    private Map<String, Object> validateCaseChange(
            String targetTable, String targetKey, DiagnosisCaseSnapshot snapshot,
            List<Map<String, Object>> rows) {
        if (isPublicRuleCandidate(snapshot.candidateSql())) {
            return Map.of("ok", true, "status", "NOT_REQUIRED", "requestedIds", List.of(),
                    "message", "本次按公共初筛规则验收记录集合差异，不使用数据澄清中的单条案例编号");
        }
        List<String> requested = expectedRecordIds(snapshot);
        String field = effectiveBusinessKey(snapshot.caseInput(), targetKey);
        String expected = text(snapshot.changeProposal().get("expectedCaseEffect"));
        if (requested.isEmpty()) {
            return Map.of("ok", true, "status", "NOT_REQUIRED", "requestedIds", List.of(),
                    "message", "本次按科室、时间、数据范围或整体结果排查，不要求单条案例编号验收");
        }
        if (targetKey.isBlank()) {
            return Map.of("ok", false, "status", "UNVERIFIED", "requestedIds", requested,
                    "message", "真实库目标表没有可核对的记录编号字段，不能证明案例修改正确");
        }
        java.util.Set<String> candidate = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String value = rowValue(row, field);
            if (!value.isBlank()) candidate.add(value);
        }
        Map<String, Integer> baselineCounts = new LinkedHashMap<>();
        real.query("SELECT CONVERT(NVARCHAR(300), [" + targetKey + "]), COUNT_BIG(1) "
                        + "FROM [dbo].[" + targetTable + "] WHERE CONVERT(NVARCHAR(300), ["
                        + targetKey + "]) IN (" + requested.stream()
                                .map(DiagnosisShadowRunner::literal)
                                .collect(java.util.stream.Collectors.joining(","))
                        + ") GROUP BY [" + targetKey + "]",
                result -> {
                    baselineCounts.put(result.getString(1), result.getInt(2));
                });
        Map<String, Integer> candidateCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String value = rowValue(row, field);
            if (!value.isBlank() && requested.contains(value)) {
                candidateCounts.merge(value, 1, Integer::sum);
            }
        }
        List<String> baselineMissing = requested.stream()
                .filter(id -> baselineCounts.getOrDefault(id, 0) == 0).toList();
        List<String> candidatePresent = requested.stream().filter(candidate::contains).toList();
        List<String> candidateMissing = requested.stream()
                .filter(id -> !candidate.contains(id)).toList();
        boolean exclude = expected.contains("排除") || expected.contains("删除")
                || expected.contains("不应") || expected.contains("不纳入");
        boolean ok = exclude ? baselineMissing.isEmpty()
                && exclusionReducedAll(requested, baselineCounts, candidateCounts)
                : candidateMissing.isEmpty();
        String message = !baselineMissing.isEmpty() && exclude
                ? "以下编号在当前正式中间表中不存在，不能把候选结果中的缺失误判为修复成功："
                        + String.join("、", baselineMissing)
                : ok ? (exclude ? "全部案例原来存在，候选抽取后对应记录数已按要求减少"
                        : "全部案例已按要求进入候选抽取结果")
                : "候选结果未让全部案例按实施要求变化";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok);
        result.put("status", ok ? "VERIFIED" : "UNVERIFIED");
        result.put("expectedAction", exclude ? "EXCLUDE" : "INCLUDE");
        result.put("requestedIds", requested);
        result.put("baselinePresentIds", requested.stream()
                .filter(id -> baselineCounts.getOrDefault(id, 0) > 0).toList());
        result.put("baselineMissingIds", baselineMissing);
        result.put("candidatePresentIds", candidatePresent);
        result.put("candidateMissingIds", candidateMissing);
        result.put("baselineCounts", baselineCounts);
        result.put("candidateCounts", candidateCounts);
        result.put("message", message);
        return Map.copyOf(result);
    }

    static boolean isPublicRuleCandidate(Map<String, Object> candidateSql) {
        return candidateSql.get("publicRuleIds") instanceof List<?> ruleIds
                && !ruleIds.isEmpty();
    }

    private record ShadowColumn(String name, String type, boolean nullable) {}

    static boolean exclusionReducedAll(
            List<String> requested, Map<String, Integer> baseline, Map<String, Integer> candidate) {
        return requested.stream().allMatch(id -> candidate.getOrDefault(id, 0)
                < baseline.getOrDefault(id, 0));
    }

    static List<String> newDuplicateKeys(
            Map<String, Integer> baseline, Map<String, Integer> candidate) {
        return candidate.entrySet().stream()
                .filter(entry -> entry.getValue() > Math.max(1,
                        baseline.getOrDefault(entry.getKey(), 0)))
                .map(Map.Entry::getKey).limit(20).toList();
    }

    private String resolveTargetKey(String targetTable, String recordField) {
        List<String> columns = real.queryForList(
                "SELECT [COLUMN_NAME] FROM [INFORMATION_SCHEMA].[COLUMNS] "
                        + "WHERE [TABLE_SCHEMA]='dbo' AND [TABLE_NAME]=?",
                String.class, targetTable);
        List<String> candidates = text(recordField).isBlank()
                ? DEFAULT_BUSINESS_KEYS : keyCandidates(recordField, false);
        for (String candidate : candidates) {
            if (columns.stream().anyMatch(value -> candidate.equalsIgnoreCase(value))) {
                return candidate;
            }
        }
        return "";
    }

    private static String effectiveBusinessKey(Map<String, Object> caseInput, String targetKey) {
        String configured = text(caseInput.get("businessUniqueKey"));
        if (!configured.isBlank()) return configured;
        String recordField = text(caseInput.get("recordField"));
        return recordField.isBlank() ? text(targetKey) : recordField;
    }

    private static List<String> expectedRecordIds(DiagnosisCaseSnapshot snapshot) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(
                DiagnosisCaseService.recordIds(snapshot.caseInput()));
        Object rows = snapshot.dataConfirmation().get("overIncludedRows");
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> value) {
                    String id = text(value.get("recordId"));
                    if (!id.isBlank()) ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static String rowValue(Map<String, Object> row, String recordField) {
        for (String candidate : keyCandidates(recordField, true)) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (candidate.equalsIgnoreCase(entry.getKey())) return text(entry.getValue());
            }
        }
        return "";
    }

    private static List<String> keyCandidates(String recordField, boolean source) {
        return switch (text(recordField).toUpperCase(Locale.ROOT)) {
            case "ENCOUNTER_ID" -> source
                    ? List.of("ENCOUNTER_ID", "encounterId", "bizId") : List.of("ENCOUNTER_ID");
            case "EVENT_ID" -> source
                    ? List.of("EVENT_ID", "eventId", "bizId") : List.of("EVENT_ID");
            case "ORDER_ID" -> source
                    ? List.of("ORDER_ID", "CLI_ORDER_ID", "orderId", "bizId")
                    : List.of("ORDER_ID", "CLI_ORDER_ID");
            case "SURGERY_ID" -> source
                    ? List.of("SURGERY_ID", "surgeryId", "bizId") : List.of("SURGERY_ID");
            default -> List.of(recordField);
        };
    }

    private String sourceSql(EntityPageData entity) {
        return sqlDialects == null ? entity.sourceTableSql() : sqlDialects.sourceTableSql(entity);
    }

    private String currentNodeSql(EntityPageData entity, String layer, String nodeId) {
        if ("SOURCE_EXTRACT".equals(layer) || "source-extract-sql".equals(nodeId)) {
            return sourceSql(entity);
        }
        return switch (nodeId) {
            case "department-sql" -> entity.deptStatSql();
            case "patient-sql" -> entity.patientDetailSql();
            default -> entity.overviewSql();
        };
    }

    private String sourceExecutableSql(String sql, boolean fromRealDatabase) {
        boolean oracleTarget = !fromRealDatabase && sqlDialects != null
                && sqlDialects.oracleActive();
        sql = DiagnosisCaseService.normalizeDurationCandidateForExecution(sql, oracleTarget);
        if (dialectsParser == null) return sql;
        String dialect = !oracleTarget
                ? DbTypeEnum.SQLSERVER.code : DbTypeEnum.ORACLE.code;
        String converted = dialectsParser.convertQuerySyntax(sql, dialect);
        return DbTypeEnum.ORACLE.code.equals(dialect)
                ? SqlAliasConverter.convert(converted) : converted;
    }

    /**
     * Oracle DATE/TIMESTAMP columns must receive JDBC temporal values.  The
     * indicator parameter mapper intentionally returns display-friendly
     * strings because overview SQL runs on SQL Server, while the formal
     * extraction endpoint receives {@link java.util.Date} values.  Shadow
     * extraction therefore normalizes only the four well-known time
     * parameters before binding them; other parameters remain untouched.
     */
    private static Map<String, Object> jdbcSourceParams(Map<String, Object> params) {
        Map<String, Object> normalized = new LinkedHashMap<>(params);
        for (String name : List.of("startTime", "endTime", "marptBeginAt", "marptEndAt")) {
            Object value = normalized.get(name);
            if (value instanceof String text && !text.isBlank()) {
                normalized.put(name, Timestamp.valueOf(text.strip()));
            }
        }
        return normalized;
    }

    private static String literal(String value) {
        return "N'" + text(value).replace("'", "''") + "'";
    }

    private static String toUpperSnake(String value) {
        return text(value).replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }

    private static String replaceTable(String sql, String target, String shadow) {
        if (sql == null || sql.isBlank()) return sql;
        assertSessionShadowName(shadow);
        return sql.replaceAll("(?i)(?<![A-Za-z0-9_])(?:\\[?dbo\\]?\\.)?\\[?"
                + java.util.regex.Pattern.quote(target) + "\\]?(?![A-Za-z0-9_])",
                "[" + shadow + "]");
    }

    private static String safe(String value, int limit) {
        String normalized = text(value).replaceAll("[^A-Za-z0-9_]", "_");
        return normalized.substring(0, Math.min(normalized.length(), limit));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 SQL 哈希", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName()
                : value.replaceAll("(?i)(password|pwd|token)=[^;\\s]+", "$1=***");
    }
}

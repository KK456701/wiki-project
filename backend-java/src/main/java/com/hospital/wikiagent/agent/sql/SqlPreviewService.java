package com.hospital.wikiagent.agent.sql;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.dbhub.DatabaseSourceException;

/** Executes a displayed indicator SQL as a bounded, read-only preview. */
@Service
public class SqlPreviewService {
    public static final int MAX_ROWS = 200;
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern DECLARE = Pattern.compile(
            "(?im)^\\s*DECLARE\\s+@([A-Za-z_][A-Za-z0-9_]*)\\s+[^=;]+="
                    + "\\s*(N?'(?:''|[^'])*'|NULL|-?\\d+(?:\\.\\d+)?)\\s*;"
                    + "\\s*(?:--[^\\r\\n]*)?$");
    private static final Pattern NOCOUNT = Pattern.compile(
            "(?im)^\\s*SET\\s+NOCOUNT\\s+ON\\s*;\\s*$");
    private static final Set<String> METADATA_TABLES = Set.of(
            "INFORMATION_SCHEMA.TABLES", "INFORMATION_SCHEMA.COLUMNS",
            "SYS.TABLES", "SYS.COLUMNS", "SYS.OBJECTS", "SYS.SCHEMAS");

    private final ReadOnlySqlValidator validator;
    private final MrasSqlLineageAnalyzer lineage;
    private final EntityPageParser entities;
    private final ObjectProvider<JdbcTemplate> businessTemplates;
    private final ObjectProvider<JdbcTemplate> realTemplates;

    public SqlPreviewService(
            ReadOnlySqlValidator validator,
            MrasSqlLineageAnalyzer lineage,
            EntityPageParser entities,
            @Qualifier("bizDbJdbcTemplate") ObjectProvider<JdbcTemplate> businessTemplates,
            @Qualifier("sqlServerJdbcTemplate") ObjectProvider<JdbcTemplate> realTemplates) {
        this.validator = validator;
        this.lineage = lineage;
        this.entities = entities;
        this.businessTemplates = businessTemplates;
        this.realTemplates = realTemplates;
    }

    public PreviewResult execute(HospitalPrincipal principal, PreviewRequest request) {
        DatabaseRole role = role(request.databaseRole());
        EntityPageData entity = entity(principal.hospitalId(), request.ruleId(), request.profileId());
        String executable = normalize(request.sql(), role, request.statStart(), request.statEnd());
        ReadOnlySqlValidator.ValidationResult checked = validator.validateAdHocReadOnly(executable);
        if (!checked.ok()) throw new IllegalArgumentException(checked.message());
        validateTables(entity, executable, role);

        JdbcTemplate template = role == DatabaseRole.BUSINESS
                ? businessTemplates.getIfAvailable() : realTemplates.getIfAvailable();
        if (template == null) {
            throw new DatabaseSourceException("SQL_PREVIEW_SOURCE_DISABLED",
                    role == DatabaseRole.BUSINESS
                            ? "Oracle 业务库未启用，无法执行 SQL。"
                            : "SQL Server 中间库未启用，无法执行 SQL。",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        long started = System.currentTimeMillis();
        try {
            QueryData data = template.query(executable, statement -> {
                statement.setMaxRows(MAX_ROWS + 1);
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            }, (ResultSetExtractor<QueryData>)
                    resultSet -> queryData(resultSet, MAX_ROWS + 1));
            List<Map<String, Object>> values = data.rows();
            boolean truncated = values.size() > MAX_ROWS;
            List<Map<String, Object>> visible = truncated
                    ? List.copyOf(values.subList(0, MAX_ROWS)) : List.copyOf(values);
            return new PreviewResult(
                    "SQLRUN_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    role.name(), role == DatabaseRole.BUSINESS ? "Oracle 业务库" : "SQL Server 中间库",
                    "COMPLETED", visible.size(), truncated, data.columns(), visible,
                    System.currentTimeMillis() - started, executable);
        } catch (RuntimeException exception) {
            throw new DatabaseSourceException("SQL_PREVIEW_EXECUTION_FAILED",
                    "只读 SQL 执行失败：" + safeMessage(exception), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    String normalize(String sql, DatabaseRole role, String statStart, String statEnd) {
        String result = sql == null ? "" : sql.strip();
        if (result.length() > 200_000) throw new IllegalArgumentException("SQL 长度超过 200000 字符");
        result = result.replaceFirst("^['\"]\\s*(?=--|SELECT|WITH)", "");
        result = result.replaceFirst("\\s*['\"]$", "").strip();
        if (role == DatabaseRole.REAL) {
            Map<String, String> declarations = new LinkedHashMap<>();
            Matcher matcher = DECLARE.matcher(result);
            StringBuffer body = new StringBuffer();
            while (matcher.find()) {
                declarations.put(matcher.group(1), matcher.group(2));
                matcher.appendReplacement(body, "");
            }
            matcher.appendTail(body);
            result = NOCOUNT.matcher(body.toString()).replaceAll("").strip();
            for (Map.Entry<String, String> declaration : declarations.entrySet()) {
                result = result.replaceAll("(?i)(?<![A-Za-z0-9_])@"
                        + Pattern.quote(declaration.getKey()) + "\\b",
                        Matcher.quoteReplacement(declaration.getValue()));
            }
        }
        LocalDateTime start = parseTime(statStart);
        LocalDateTime end = parseTime(statEnd);
        if (Pattern.compile(":(?:start_time|startTime|marptBeginAt)\\b").matcher(result).find()
                && start == null) {
            throw new IllegalArgumentException("当前 SQL 需要统计开始时间，页面上下文未提供");
        }
        if (Pattern.compile(":(?:end_time|endTime|marptEndAt)\\b").matcher(result).find()
                && end == null) {
            throw new IllegalArgumentException("当前 SQL 需要统计结束时间，页面上下文未提供");
        }
        result = bindPeriod(result, role, start, end);
        return result.replaceFirst(";+\\s*$", "").strip();
    }

    private void validateTables(EntityPageData entity, String sql, DatabaseRole role) {
        Set<String> allowed = new LinkedHashSet<>();
        String[] registered = role == DatabaseRole.BUSINESS
                ? new String[] {entity.sourceTableSql(), optionalString(entity, "oracleSourceTableSql")}
                : new String[] {entity.overviewSql(), entity.deptStatSql(), entity.patientDetailSql()};
        for (String value : registered) {
            if (value != null && !value.isBlank()) allowed.addAll(lineage.analyze(value).tables());
        }
        if (role == DatabaseRole.BUSINESS && entity.extendedEvents() != null) {
            entity.extendedEvents().forEach(item ->
                    allowed.addAll(lineage.analyze(item.getValue()).tables()));
        }
        if (role == DatabaseRole.BUSINESS) {
            optionalEntries(entity, "oracleExtendedEvents").forEach(item ->
                    allowed.addAll(lineage.analyze(item.getValue()).tables()));
        }
        if (role == DatabaseRole.REAL && entity.targetTable() != null && !entity.targetTable().isBlank()) {
            allowed.add(entity.targetTable().toUpperCase(Locale.ROOT));
        }
        Set<String> denied = new LinkedHashSet<>();
        for (String table : lineage.analyze(sql).tables()) {
            String normalized = table.toUpperCase(Locale.ROOT);
            if (!allowed.contains(normalized) && !METADATA_TABLES.contains(normalized)) denied.add(table);
        }
        if (!denied.isEmpty()) {
            throw new IllegalArgumentException("SQL 访问了当前指标链路外的表：" + denied);
        }
    }

    private EntityPageData entity(String hospitalId, String ruleId, String profileId) {
        String key = profileId == null || profileId.isBlank() ? ruleId : profileId;
        EntityPageData value = hospitalEntity(key, hospitalId);
        if (value == null && ruleId != null && !ruleId.equals(key)) {
            value = hospitalEntity(ruleId, hospitalId);
        }
        if (value == null) throw new IllegalArgumentException("当前指标口径不存在，不能执行 SQL");
        return value;
    }

    private EntityPageData hospitalEntity(String key, String hospitalId) {
        try {
            Method method = entities.getClass().getMethod("getEntity", String.class, String.class);
            Object value = method.invoke(entities, key, hospitalId);
            if (value instanceof EntityPageData entity) return entity;
        } catch (ReflectiveOperationException ignored) {
            // Older personal-repository baselines do not yet expose hospital overlays.
        }
        return entities.getEntity(key);
    }

    private static String optionalString(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof String text ? text : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map.Entry<String, String>> optionalEntries(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof List<?> values
                    ? (List<Map.Entry<String, String>>) values : List.of();
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
    }

    private static DatabaseRole role(String value) {
        try {
            DatabaseRole result = DatabaseRole.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
            if (result != DatabaseRole.BUSINESS && result != DatabaseRole.REAL) {
                throw DatabaseSourceException.invalid();
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw DatabaseSourceException.invalid();
        }
    }

    private static String bindPeriod(
            String sql, DatabaseRole role, LocalDateTime start, LocalDateTime end) {
        String startLiteral = literal(role, start);
        String endLiteral = literal(role, end);
        String result = sql;
        for (String name : List.of("start_time", "startTime", "marptBeginAt")) {
            result = result.replaceAll("(?<!:):" + Pattern.quote(name) + "\\b",
                    Matcher.quoteReplacement(startLiteral));
        }
        for (String name : List.of("end_time", "endTime", "marptEndAt")) {
            result = result.replaceAll("(?<!:):" + Pattern.quote(name) + "\\b",
                    Matcher.quoteReplacement(endLiteral));
        }
        result = result.replaceAll("(?<!:):syncType\\b", "'outHosp'");
        return result;
    }

    private static String literal(DatabaseRole role, LocalDateTime value) {
        if (value == null) return "NULL";
        String formatted = value.format(SQL_TIME);
        return role == DatabaseRole.BUSINESS
                ? "TIMESTAMP '" + formatted + "'" : "'" + formatted + "'";
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().replace('T', ' ');
        try {
            if (normalized.length() == 10) return LocalDate.parse(normalized).atStartOfDay();
            return LocalDateTime.parse(normalized.substring(0, Math.min(19, normalized.length())), SQL_TIME);
        } catch (DateTimeParseException | IndexOutOfBoundsException exception) {
            throw new IllegalArgumentException("统计时间格式无效，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private static QueryData queryData(ResultSet resultSet, int limit) throws java.sql.SQLException {
        List<Map<String, Object>> values = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columns = metadata.getColumnCount();
        List<String> labels = new ArrayList<>(columns);
        for (int index = 1; index <= columns; index++) labels.add(metadata.getColumnLabel(index));
        while (resultSet.next() && values.size() < limit) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= columns; index++) {
                row.put(labels.get(index - 1), resultSet.getObject(index));
            }
            values.add(row);
        }
        return new QueryData(List.copyOf(labels), values);
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) return current.getClass().getSimpleName();
        return message.replaceAll("(?i)(password|pwd)\\s*=\\s*[^;\\s]+", "$1=***");
    }

    public record PreviewRequest(
            String sql, String databaseRole, String ruleId, String profileId,
            String statStart, String statEnd) {}

    record QueryData(List<String> columns, List<Map<String, Object>> rows) {}

    public record PreviewResult(
            String executionId, String databaseRole, String databaseLabel, String status,
            int rowCount, boolean truncated, List<String> columns,
            List<Map<String, Object>> rows, long durationMs, String executedSql) {}
}

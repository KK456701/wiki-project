package com.hospital.wikiagent.agent.extraction;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.SqlParameterBinder;
import com.hospital.wikiagent.sqlserver.RealDatabaseSafetyPolicy;
import com.hospital.wikiagent.sqlserver.RealDatabaseSafetyVerifier;

/**
 * 通过只读 DBHub MCP 执行当前 Profile 的受控抽取 SQL，并原子替换试运行真实库快照。
 *
 * <p>该类型是应用内唯一允许写入 {@code winex_aima} 的边界。抽取 SQL 只能来自已验证
 * 的不可变知识发布包，时间与医院参数由服务端绑定；写锁会一直保持到本轮真实库计算
 * 消费完该快照。</p>
 */
@Component
@ConditionalOnProperty(prefix = "wiki.agent.extraction", name = "mode", havingValue = "required")
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class BusinessMcpSourceExtractionGateway implements SourceExtractionGateway {
    private static final String APP_LOCK = "winex_aima:global-profile-snapshot";
    private static final String BUSINESS_SCHEMA = "WINDBA_GN";
    private static final int ENCOUNTER_ID_BATCH_SIZE = 500;
    private static final AtomicLong SYNTHETIC_ID =
            new AtomicLong(System.currentTimeMillis() * 1000);
    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper PRECISE_JSON = new ObjectMapper();
    private static final TypeReference<List<LinkedHashMap<String, Object>>> PRECISE_ROWS =
            new TypeReference<>() { };
    private static final Set<String> PATIENT_TABLES = Set.of(
            "INP_CLI_ORDER",
            "INP_SURGICAL_ANESTHESIA_PLAN",
            "INPAT_TRANSFER",
            "INPATIENT_ENCOUNTER",
            "MRAS_INDEX_SURGREC",
            "MRAS_PATIENT_EVENT");

    private final DbHubMcpClient dbHub;
    private final DbHubProperties dbHubProperties;
    private final SqlParameterBinder parameterBinder;
    private final ReadOnlySqlValidator sqlValidator;
    private final DataSource dataSource;
    private final RealDatabaseSafetyVerifier safetyVerifier;

    public BusinessMcpSourceExtractionGateway(
            DbHubMcpClient dbHub,
            DbHubProperties dbHubProperties,
            SqlParameterBinder parameterBinder,
            ReadOnlySqlValidator sqlValidator,
            @Qualifier("sqlServerDataSource") DataSource dataSource,
            RealDatabaseSafetyVerifier safetyVerifier) {
        this.dbHub = dbHub;
        this.dbHubProperties = dbHubProperties;
        this.parameterBinder = parameterBinder;
        this.sqlValidator = sqlValidator;
        this.dataSource = dataSource;
        this.safetyVerifier = safetyVerifier;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        try (SourceExtractionLease lease = prepare(request)) {
            return lease.result();
        }
    }

    @Override
    public SourceExtractionLease prepare(ExtractionRequest request) {
        String extractionId = "EXT_" + UUID.randomUUID().toString().replace("-", "");
        Connection connection = null;
        try {
            safetyVerifier.verify();
            ExtractionContract contract = ExtractionContract.from(request.extractionContract());
            if (request.hospitalSoid() == null) {
                return SourceExtractionLease.completed(failed(
                        extractionId, "EXTRACTION_HOSPITAL_SOID_MISSING",
                        "未配置业务 MCP 所需的医院 SOID。"));
            }

            connection = dataSource.getConnection();
            acquireApplicationLock(connection);
            Map<String, List<Map<String, Object>>> sourceRows =
                    fetchAll(request, contract);
            Map<String, PreparedTable> prepared =
                    prepareTables(connection, sourceRows, contract);
            long sourceCount = sourceRows.values().stream().mapToLong(Collection::size).sum();
            long inserted = replaceSnapshot(connection, prepared);
            String targetSnapshot = "SNAP_" + UUID.randomUUID().toString().replace("-", "");
            ExtractionResult result = new ExtractionResult(
                    extractionId,
                    ExtractionResult.Status.SUCCESS,
                    sourceCount,
                    inserted,
                    0,
                    0,
                    java.time.Instant.now(),
                    request.idempotencyKey(),
                    targetSnapshot,
                    "",
                    "已按事件契约刷新真实库快照。");
            Connection lockedConnection = connection;
            AtomicBoolean released = new AtomicBoolean();
            connection = null;
            return new SourceExtractionLease(result, () -> {
                if (released.compareAndSet(false, true)) {
                    releaseAndClose(lockedConnection);
                }
            });
        } catch (RuntimeException | SQLException exception) {
            closeQuietly(connection);
            return SourceExtractionLease.completed(failed(
                    extractionId,
                    errorCode(exception),
                    safeMessage(exception)));
        }
    }

    private Map<String, List<Map<String, Object>>> fetchAll(
            ExtractionRequest request,
            ExtractionContract contract) {
        if (!DbHubProperties.BUSINESS_SOURCE_ID.equalsIgnoreCase(
                text(request.businessSourceId()))) {
            throw new ExtractionFailure(
                    "EXTRACTION_CONTRACT_INVALID", "源数据抽取只能使用 winex_all_dev。");
        }
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        List<Map<String, Object>> eventRows = List.of();
        if (contract.route() == Route.EVENT) {
            eventRows = sourceRows(request);
            tables.put(contract.eventTable(), eventRows);
        }

        Set<String> encounterIds = encounterIds(eventRows);
        for (String table : contract.dependencyTables()) {
            tables.put(table, dependencyRows(
                    table,
                    contract.allowedFields().get(table),
                    encounterIds,
                    request.hospitalSoid()));
        }
        return tables;
    }

    private List<Map<String, Object>> sourceRows(ExtractionRequest request) {
        String sourceSql = text(request.sourceSql());
        if (sourceSql.isBlank()) {
            throw new ExtractionFailure(
                    "SOURCE_EXTRACT_SQL_UNAVAILABLE", "当前 Profile 缺少可执行的抽取 SQL。");
        }
        if (!sha256(sourceSql).equalsIgnoreCase(text(request.sourceSqlSha256()))) {
            throw new ExtractionFailure(
                    "SOURCE_EXTRACT_SQL_INVALID", "抽取 SQL 与当前知识发布摘要不一致。");
        }
        ReadOnlySqlValidator.ValidationResult validation =
                sqlValidator.validateReadOnly(sourceSql);
        if (!validation.ok()) {
            throw new ExtractionFailure(
                    "SOURCE_EXTRACT_SQL_INVALID", validation.message());
        }
        String start = SQL_TIME.format(request.statStart());
        String end = SQL_TIME.format(request.statEnd());
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("start_time", start);
        parameters.put("end_time", end);
        parameters.put("startTime", start);
        parameters.put("endTime", end);
        parameters.put("marptBeginAt", start);
        parameters.put("marptEndAt", end);
        parameters.put("hospitalSOID", request.hospitalSoid());
        String bound;
        try {
            bound = parameterBinder.bind(sourceSql, parameters)
                    .replaceFirst(";\\s*$", "");
        } catch (IllegalArgumentException exception) {
            throw new ExtractionFailure(
                    "SOURCE_EXTRACT_SQL_INVALID", exception.getMessage());
        }
        String hospitalScoped = "SELECT * FROM (" + bound
                + ") AS [profile_source] WHERE [profile_source].[hospitalSoid] = "
                + request.hospitalSoid();
        return executePreciseSql(hospitalScoped);
    }

    private List<Map<String, Object>> dependencyRows(
            String table,
            Set<String> allowedFields,
            Set<String> encounterIds,
            Long hospitalSoid) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw new ExtractionFailure(
                    "EXTRACTION_CONTRACT_INVALID", table + " 缺少允许结果字段。");
        }
        List<String> columns = allowedFields.stream().sorted().toList();
        if (PATIENT_TABLES.contains(table)) {
            if (encounterIds.isEmpty()) {
                return List.of();
            }
            if (!allowedFields.contains("ENCOUNTER_ID")) {
                throw new ExtractionFailure(
                        "EXTRACTION_CONTRACT_INVALID",
                        table + " 患者依赖表缺少 ENCOUNTER_ID 字段。");
            }
            List<String> ids = encounterIds.stream()
                    .map(BusinessMcpSourceExtractionGateway::numericId)
                    .toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int offset = 0; offset < ids.size(); offset += ENCOUNTER_ID_BATCH_SIZE) {
                List<String> batch = ids.subList(
                        offset, Math.min(offset + ENCOUNTER_ID_BATCH_SIZE, ids.size()));
                rows.addAll(executePreciseSql(dependencySql(
                        sourceSchema(), table, columns, hospitalSoid,
                        "[ENCOUNTER_ID] IN (" + String.join(",", batch) + ")")));
            }
            return rows;
        }
        return executePreciseSql(
                dependencySql(sourceSchema(), table, columns, hospitalSoid, ""));
    }

    private List<Map<String, Object>> executePreciseSql(String sql) {
        String normalized = text(sql).replaceFirst(";\\s*$", "");
        String preciseSql = "SELECT COALESCE((SELECT * FROM (" + normalized
                + ") AS [precise_source] FOR JSON PATH, INCLUDE_NULL_VALUES), '[]')"
                + " AS [json_payload]";
        List<Map<String, Object>> envelope = dbHub.executeSql(
                dbHubProperties.businessSource().getExecuteTool(), preciseSql);
        if (envelope.size() != 1) {
            throw new ExtractionFailure(
                    "SOURCE_EXTRACTION_RESPONSE_INVALID",
                    "业务库抽取未返回唯一的精确结果封装。");
        }
        Object payload = valueIgnoreCase(envelope.get(0), "JSON_PAYLOAD");
        String json;
        if (payload instanceof String textPayload) {
            json = textPayload;
        } else if (payload instanceof JsonNode node && node.isTextual()) {
            json = node.asText();
        } else {
            throw new ExtractionFailure(
                    "SOURCE_EXTRACTION_RESPONSE_INVALID",
                    "业务库抽取缺少精确结果载荷。");
        }
        try {
            List<LinkedHashMap<String, Object>> rows =
                    PRECISE_JSON.readValue(json, PRECISE_ROWS);
            return new ArrayList<>(rows);
        } catch (JsonProcessingException exception) {
            throw new ExtractionFailure(
                    "SOURCE_EXTRACTION_RESPONSE_INVALID",
                    "业务库抽取的精确结果载荷无法解析。");
        }
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (key.equals(snake(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String dependencySql(
            String schema,
            String table,
            List<String> columns,
            Long hospitalSoid,
            String extraCondition) {
        String select = columns.stream()
                .map(value -> "[" + value + "]")
                .collect(java.util.stream.Collectors.joining(","));
        List<String> conditions = new ArrayList<>();
        if (columns.contains("HOSPITAL_SOID")) {
            conditions.add("[HOSPITAL_SOID] = " + hospitalSoid);
        }
        if (columns.contains("IS_DEL")) {
            conditions.add("[IS_DEL] = 0");
        }
        if (extraCondition != null && !extraCondition.isBlank()) {
            conditions.add(extraCondition);
        }
        return "SELECT " + select + " FROM [" + schema + "].["
                + RealDatabaseSafetyPolicy.requireAllowedTable(table) + "]"
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));
    }

    private String sourceSchema() {
        String configured = text(dbHubProperties.businessSource().getSchemaName());
        if (!BUSINESS_SCHEMA.equalsIgnoreCase(configured)) {
            throw new ExtractionFailure(
                    "EXTRACTION_CONTRACT_INVALID",
                    "业务库抽取 Schema 必须配置为 " + BUSINESS_SCHEMA + "。");
        }
        return BUSINESS_SCHEMA;
    }

    private static String numericId(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("\\d+")) {
            throw new ExtractionFailure(
                    "EXTRACTION_SCHEMA_MISMATCH", "抽取结果包含非法 ENCOUNTER_ID。");
        }
        return normalized;
    }

    private Map<String, PreparedTable> prepareTables(
            Connection connection,
            Map<String, List<Map<String, Object>>> tables,
            ExtractionContract contract) throws SQLException {
        Map<String, PreparedTable> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : tables.entrySet()) {
            String table = RealDatabaseSafetyPolicy.requireAllowedTable(entry.getKey());
            Map<String, ColumnInfo> columns = columns(connection, table);
            if (columns.isEmpty()) {
                throw new ExtractionFailure(
                        "EXTRACTION_SCHEMA_MISMATCH", "目标表不存在或没有可写列：" + table);
            }
            String expectedFingerprint = contract.schemaFingerprints().get(table);
            if (expectedFingerprint == null
                    || !expectedFingerprint.equals(schemaFingerprint(columns.values()))) {
                throw new ExtractionFailure(
                        "EXTRACTION_SCHEMA_FINGERPRINT_MISMATCH",
                        "目标表结构与已发布契约不一致：" + table);
            }
            result.put(table, prepareTable(
                    table,
                    entry.getValue(),
                    columns,
                    contract.allowedFields().get(table),
                    table.equals(contract.eventTable())));
        }
        return result;
    }

    private PreparedTable prepareTable(
            String table,
            List<Map<String, Object>> rows,
            Map<String, ColumnInfo> columns,
            Set<String> allowedFields,
            boolean eventTable) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw new ExtractionFailure(
                    "EXTRACTION_CONTRACT_INVALID", table + " 缺少允许结果字段契约。");
        }
        if (rows.isEmpty()) {
            return new PreparedTable(table, List.of(), List.of());
        }
        List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
        LinkedHashSet<String> insertColumns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String column = snake(entry.getKey());
                ColumnInfo info = columns.get(column);
                if (!allowedFields.contains(column)) {
                    if (info == null && isBlankPlaceholder(entry.getValue())) {
                        continue;
                    }
                    throw new ExtractionFailure(
                            "EXTRACTION_SCHEMA_MISMATCH",
                            table + " 返回了知识契约未允许的字段：" + column);
                }
                if (info == null) {
                    if (entry.getValue() != null) {
                        throw new ExtractionFailure(
                                "EXTRACTION_SCHEMA_MISMATCH",
                                table + " 返回了目标表不存在的字段：" + column);
                    }
                    continue;
                }
                converted.put(column, coerce(entry.getValue(), info.type()));
                insertColumns.add(column);
            }
            String primaryKey = table + "_ID";
            if (eventTable
                    && columns.containsKey(primaryKey)
                    && allowedFields.contains(primaryKey)
                    && !converted.containsKey(primaryKey)) {
                converted.put(primaryKey, nextSyntheticId());
                insertColumns.add(primaryKey);
            }
            normalized.add(converted);
        }
        for (ColumnInfo info : columns.values()) {
            if (!info.nullable() && !info.hasDefault() && !info.identity()) {
                for (Map<String, Object> row : normalized) {
                    if (!row.containsKey(info.name()) || row.get(info.name()) == null) {
                        throw new ExtractionFailure(
                                "EXTRACTION_REQUIRED_COLUMN_MISSING",
                                table + " 缺少必填字段：" + info.name());
                    }
                }
            }
        }
        List<String> ordered = columns.keySet().stream()
                .filter(insertColumns::contains)
                .toList();
        return new PreparedTable(table, ordered, normalized);
    }

    private static boolean isBlankPlaceholder(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private long replaceSnapshot(
            Connection connection,
            Map<String, PreparedTable> tables) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String table : RealDatabaseSafetyPolicy.TABLES) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + RealDatabaseSafetyPolicy.qualified(table))) {
                    statement.executeUpdate();
                } catch (SQLException exception) {
                    throw new ExtractionFailure(
                            "REAL_DB_DELETE_FAILED",
                            "清理真实库受控表失败：" + table + sqlFailureDetails(exception));
                }
            }
            long inserted = 0;
            for (PreparedTable table : tables.values()) {
                try {
                    inserted += insert(connection, table);
                } catch (SQLException exception) {
                    throw new ExtractionFailure(
                            "REAL_DB_INSERT_FAILED",
                            "写入真实库受控表失败：" + table.table()
                                    + sqlFailureDetails(exception));
                }
            }
            try {
                connection.commit();
            } catch (SQLException exception) {
                throw new ExtractionFailure(
                        "REAL_DB_COMMIT_FAILED",
                        "提交真实库快照事务失败。" + sqlFailureDetails(exception));
            }
            return inserted;
        } catch (RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private long insert(Connection connection, PreparedTable table) throws SQLException {
        if (table.rows().isEmpty()) {
            return 0;
        }
        if (table.columns().isEmpty()) {
            throw new ExtractionFailure(
                    "EXTRACTION_SCHEMA_MISMATCH",
                    table.table() + " 有数据但没有可插入字段。");
        }
        String columns = table.columns().stream()
                .map(value -> "[" + value + "]")
                .collect(java.util.stream.Collectors.joining(","));
        String placeholders = java.util.Collections.nCopies(
                table.columns().size(), "?").stream().collect(
                        java.util.stream.Collectors.joining(","));
        String sql = "INSERT INTO " + RealDatabaseSafetyPolicy.qualified(table.table())
                + " (" + columns + ") VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map<String, Object> row : table.rows()) {
                for (int index = 0; index < table.columns().size(); index++) {
                    statement.setObject(index + 1, row.get(table.columns().get(index)));
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return table.rows().size();
    }

    private static String sqlFailureDetails(SQLException exception) {
        SQLException current = exception;
        while (current.getNextException() != null) {
            current = current.getNextException();
        }
        String state = current.getSQLState();
        String safeState = state != null && state.matches("[A-Za-z0-9]{1,10}")
                ? state
                : "UNKNOWN";
        return "（SQLState=" + safeState + "，错误号=" + current.getErrorCode() + "）";
    }

    private Map<String, ColumnInfo> columns(
            Connection connection, String table) throws SQLException {
        String sql = """
                SELECT c.name AS column_name,
                       c.column_id,
                       TYPE_NAME(c.user_type_id) AS data_type,
                       c.max_length,
                       c.precision,
                       c.scale,
                       c.is_nullable,
                       CASE WHEN dc.object_id IS NULL THEN 0 ELSE 1 END AS has_default,
                       c.is_identity
                FROM sys.columns c
                LEFT JOIN sys.default_constraints dc
                  ON dc.parent_object_id = c.object_id
                 AND dc.parent_column_id = c.column_id
                WHERE c.object_id = OBJECT_ID(?)
                ORDER BY c.column_id
                """;
        Map<String, ColumnInfo> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "dbo." + table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("column_name").toUpperCase(Locale.ROOT);
                    result.put(name, new ColumnInfo(
                            rows.getInt("column_id"),
                            name,
                            rows.getString("data_type").toLowerCase(Locale.ROOT),
                            rows.getInt("max_length"),
                            rows.getInt("precision"),
                            rows.getInt("scale"),
                            rows.getBoolean("is_nullable"),
                            rows.getBoolean("has_default"),
                            rows.getBoolean("is_identity")));
                }
            }
        }
        return result;
    }

    private static String schemaFingerprint(Collection<ColumnInfo> columns) {
        StringBuilder canonical = new StringBuilder("[");
        int index = 0;
        for (ColumnInfo column : columns) {
            if (index++ > 0) {
                canonical.append(',');
            }
            canonical.append("{\"ordinal\":").append(column.ordinal())
                    .append(",\"name\":\"").append(column.name())
                    .append("\",\"type\":\"").append(column.type())
                    .append("\",\"max_length\":").append(column.maxLength())
                    .append(",\"precision\":").append(column.precision())
                    .append(",\"scale\":").append(column.scale())
                    .append(",\"nullable\":").append(column.nullable())
                    .append(",\"identity\":").append(column.identity())
                    .append(",\"has_default\":").append(column.hasDefault())
                    .append('}');
        }
        canonical.append(']');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Set<String> encounterIds(List<Map<String, Object>> rows) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if ("ENCOUNTER_ID".equals(snake(entry.getKey())) && entry.getValue() != null) {
                    result.add(String.valueOf(entry.getValue()));
                }
            }
        }
        return result;
    }

    private static Object coerce(Object value, String type) {
        if (!(value instanceof String text) || text.isBlank()) {
            return value;
        }
        try {
            return switch (type) {
                case "datetime", "datetime2", "smalldatetime" ->
                        timestamp(text);
                case "date" -> Date.valueOf(LocalDate.parse(
                        text.length() >= 10 ? text.substring(0, 10) : text));
                case "time" -> Time.valueOf(LocalTime.parse(text));
                default -> value;
            };
        } catch (IllegalArgumentException | DateTimeParseException ignored) {
            throw new ExtractionFailure(
                    "EXTRACTION_TYPE_MISMATCH",
                    "字段值无法转换为目标 SQL Server 类型 " + type + "。");
        }
    }

    private static Timestamp timestamp(String text) {
        try {
            return Timestamp.from(Instant.parse(text));
        } catch (DateTimeParseException ignored) {
            try {
                return Timestamp.from(OffsetDateTime.parse(text).toInstant());
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return Timestamp.valueOf(LocalDateTime.parse(text));
                } catch (DateTimeParseException ignoredLocal) {
                    return Timestamp.valueOf(text.replace('T', ' '));
                }
            }
        }
    }

    private static String snake(String value) {
        if (value == null || value.isBlank()) {
            throw new ExtractionFailure("EXTRACTION_SCHEMA_MISMATCH", "抽取结果包含空字段名。");
        }
        String snake = value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]", "_")
                .toUpperCase(Locale.ROOT);
        if (!snake.matches("[A-Z][A-Z0-9_]*")) {
            throw new ExtractionFailure(
                    "EXTRACTION_SCHEMA_MISMATCH", "抽取结果包含非法字段名。");
        }
        return snake;
    }

    private static void acquireApplicationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DECLARE @result int;
                EXEC @result = sys.sp_getapplock
                  @Resource = ?,
                  @LockMode = 'Exclusive',
                  @LockOwner = 'Session',
                  @LockTimeout = 60000;
                SELECT @result;
                """)) {
            statement.setString(1, APP_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) < 0) {
                    throw new ExtractionFailure(
                            "REAL_DB_LOCK_TIMEOUT", "等待真实库全局快照锁超时。");
                }
            }
        }
    }

    private static void releaseAndClose(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "EXEC sys.sp_releaseapplock @Resource = ?, @LockOwner = 'Session'")) {
            statement.setString(1, APP_LOCK);
            statement.execute();
        } catch (SQLException ignored) {
            // 连接关闭会释放 Session 锁。
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing else can be done safely.
            }
        }
    }

    private static ExtractionResult failed(
            String extractionId, String code, String message) {
        return new ExtractionResult(
                extractionId,
                ExtractionResult.Status.FAILED,
                0, 0, 0, 0,
                java.time.Instant.now(),
                "", "", code, message);
    }

    private static String errorCode(Exception exception) {
        if (exception instanceof ExtractionFailure failure) {
            return failure.code();
        }
        if (exception instanceof SQLException) {
            return "REAL_DB_REPLACE_FAILED";
        }
        if (exception instanceof DbHubMcpException) {
            return "SOURCE_EXTRACTION_MCP_FAILED";
        }
        return "SOURCE_EXTRACTION_FAILED";
    }

    private static String safeMessage(Exception exception) {
        if (exception instanceof ExtractionFailure failure) {
            return failure.getMessage();
        }
        if (exception instanceof DbHubMcpException) {
            return "业务库只读 MCP 抽取失败，真实库尚未清空或替换。";
        }
        return "真实库快照准备失败，未使用旧快照继续计算。";
    }

    private enum Route {
        EVENT,
        TABLE_DOMAIN
    }

    private record ExtractionContract(
            Route route,
            String eventNo,
            String eventTable,
            List<String> dependencyTables,
            Map<String, Set<String>> allowedFields,
            Map<String, String> schemaFingerprints) {
        static ExtractionContract from(Map<String, Object> values) {
            if (values == null || values.isEmpty()) {
                throw new ExtractionFailure(
                        "EXTRACTION_CONTRACT_INVALID", "当前 Profile 缺少抽取契约。");
            }
            if (!"winex_aima".equalsIgnoreCase(text(values.get("database_name")))
                    || !"dbo".equalsIgnoreCase(text(values.get("schema_name")))) {
                throw new ExtractionFailure(
                        "EXTRACTION_CONTRACT_INVALID", "抽取契约只能指向 winex_aima.dbo。");
            }
            Route route;
            try {
                route = Route.valueOf(text(values.get("route")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new ExtractionFailure(
                        "EXTRACTION_CONTRACT_INVALID", "抽取契约路由无效。");
            }
            String eventNo = text(values.get("event_no"));
            String eventTable = text(values.get("event_table"));
            List<String> dependencies = stringList(values.get("dependency_tables")).stream()
                    .map(ExtractionContract::allowedTable)
                    .distinct()
                    .toList();
            if (route == Route.EVENT) {
                if (!eventNo.matches("[A-Z][A-Z0-9_]*") || eventTable.isBlank()) {
                    throw new ExtractionFailure(
                            "EXTRACTION_CONTRACT_INVALID", "事件抽取契约缺少事件号或目标表。");
                }
                eventTable = allowedTable(eventTable);
            } else if (dependencies.isEmpty()) {
                throw new ExtractionFailure(
                        "EXTRACTION_CONTRACT_INVALID", "表域抽取契约没有固定依赖表。");
            }
            List<String> expectedTables = new ArrayList<>();
            if (!eventTable.isBlank()) {
                expectedTables.add(eventTable);
            }
            expectedTables.addAll(dependencies);
            List<String> declaredTables = stringList(values.get("target_tables")).stream()
                    .map(ExtractionContract::allowedTable)
                    .distinct()
                    .toList();
            if (!declaredTables.equals(expectedTables)) {
                throw new ExtractionFailure(
                        "EXTRACTION_CONTRACT_INVALID",
                        "抽取契约目标表与事件/依赖表不一致。");
            }
            Map<String, Set<String>> allowedFields =
                    allowedFields(values.get("allowed_result_fields"), declaredTables);
            Map<String, String> fingerprints =
                    fingerprints(values.get("target_schema_fingerprints"), declaredTables);
            return new ExtractionContract(
                    route, eventNo, eventTable, dependencies,
                    allowedFields, fingerprints);
        }

        private static String allowedTable(String value) {
            try {
                return RealDatabaseSafetyPolicy.requireAllowedTable(value);
            } catch (IllegalArgumentException exception) {
                throw new ExtractionFailure(
                        "EXTRACTION_CONTRACT_INVALID",
                        "抽取契约包含越权或非法目标表。");
            }
        }

        private static Map<String, Set<String>> allowedFields(
                Object raw, List<String> tables) {
            Map<String, Object> source = objectMap(raw);
            Map<String, Set<String>> result = new LinkedHashMap<>();
            for (String table : tables) {
                Set<String> fields = stringList(source.get(table)).stream()
                        .map(BusinessMcpSourceExtractionGateway::snake)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (fields.isEmpty()) {
                    throw new ExtractionFailure(
                            "EXTRACTION_CONTRACT_INVALID",
                            table + " 缺少允许结果字段。");
                }
                result.put(table, fields);
            }
            return Map.copyOf(result);
        }

        private static Map<String, String> fingerprints(
                Object raw, List<String> tables) {
            Map<String, Object> source = objectMap(raw);
            Map<String, String> result = new LinkedHashMap<>();
            for (String table : tables) {
                String fingerprint = text(source.get(table)).toLowerCase(Locale.ROOT);
                if (!fingerprint.matches("[a-f0-9]{64}")) {
                    throw new ExtractionFailure(
                            "EXTRACTION_CONTRACT_INVALID",
                            table + " 缺少目标结构指纹。");
                }
                result.put(table, fingerprint);
            }
            return Map.copyOf(result);
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static long nextSyntheticId() {
        long floor = System.currentTimeMillis() * 1000;
        return SYNTHETIC_ID.updateAndGet(previous -> Math.max(previous + 1, floor));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record ColumnInfo(
            int ordinal,
            String name,
            String type,
            int maxLength,
            int precision,
            int scale,
            boolean nullable,
            boolean hasDefault,
            boolean identity) {
    }

    private record PreparedTable(
            String table, List<String> columns, List<Map<String, Object>> rows) {
    }

    private static final class ExtractionFailure extends RuntimeException {
        private final String code;

        private ExtractionFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}

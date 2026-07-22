package com.hospital.wikiagent.details;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.hospital.wikiagent.details.DetailContracts.DetailColumn;
import com.hospital.wikiagent.details.DetailContracts.RunContext;

import jakarta.annotation.PostConstruct;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
/**
 * 封装 {@code IndicatorDetailRepository} 对应数据的持久化与查询，避免上层依赖具体存储实现。
 */
public class IndicatorDetailRepository {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IndicatorDetailRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initialize() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_indicator_detail_snapshot (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      snapshot_id VARCHAR(64) NOT NULL UNIQUE,
                      run_id VARCHAR(64) NOT NULL UNIQUE,
                      hospital_id VARCHAR(64) NOT NULL,
                      rule_id VARCHAR(64) NOT NULL,
                      relative_path VARCHAR(512) NOT NULL,
                      file_sha256 VARCHAR(64),
                      denominator_count INT,
                      numerator_count INT,
                      unmatched_count INT,
                      column_schema_json TEXT,
                      status VARCHAR(32) NOT NULL,
                      created_by VARCHAR(64) NOT NULL,
                      created_at TIMESTAMP NOT NULL,
                      expires_at TIMESTAMP NOT NULL,
                      error_message TEXT
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS med_indicator_export (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      export_id VARCHAR(64) NOT NULL UNIQUE,
                      snapshot_id VARCHAR(64) NOT NULL,
                      run_id VARCHAR(64) NOT NULL,
                      hospital_id VARCHAR(64) NOT NULL,
                      rule_id VARCHAR(64) NOT NULL,
                      relative_path VARCHAR(512) NOT NULL,
                      file_name VARCHAR(255) NOT NULL,
                      file_sha256 VARCHAR(64),
                      status VARCHAR(32) NOT NULL,
                      row_count INT NOT NULL,
                      created_by VARCHAR(64) NOT NULL,
                      created_at TIMESTAMP NOT NULL,
                      expires_at TIMESTAMP NOT NULL,
                      download_count INT NOT NULL DEFAULT 0,
                      last_downloaded_at TIMESTAMP NULL,
                      error_message TEXT
                    )
                    """);
        } catch (RuntimeException ignored) {
            // 详情能力会在实际调用时返回运行库错误，不阻止影子服务启动。
        }
    }

    public Optional<RunContext> loadRun(String runId, String hospitalId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT * FROM med_sql_run_log WHERE run_id=? AND hospital_id=?",
                IndicatorDetailRepository::rowMap, runId, hospitalId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        if (!"success".equals(text(row.get("run_status")))) {
            return Optional.empty();
        }
        Map<String, Object> snapshot = map(row.get("run_context_json"));
        Map<String, Object> rule = objectMap(snapshot.get("effective_rule"));
        Map<String, Object> mapping = objectMap(snapshot.get("field_mapping"));
        Map<String, Object> parameters = objectMap(snapshot.get("params"));
        String start = first(text(snapshot.get("stat_start")), sqlTime(row.get("stat_start_time")));
        String end = first(text(snapshot.get("stat_end")), sqlTime(row.get("stat_end_time")));
        parameters.put("start_time", start);
        parameters.put("end_time", end);
        Map<String, Object> calculation = objectMap(rule.get("calculation_definition"));
        if (calculation.isEmpty()) {
            calculation = objectMap(rule.get("national_calculation_definition"));
        }
        return Optional.of(new RunContext(
                runId,
                text(row.get("sql_id")),
                hospitalId,
                text(row.get("rule_id")),
                text(rule.get("rule_name")),
                first(text(rule.get("effective_level")), "national"),
                nullableText(rule.get("national_version")),
                integer(rule.get("hospital_version")),
                start,
                end,
                first(text(mapping.get("db_name")), text(snapshot.get("db_source_id"))),
                text(mapping.get("main_table")),
                first(text(mapping.get("dialect")), "sqlserver"),
                text(mapping.get("query_profile")),
                calculation,
                mapping,
                parameters,
                objectMap(snapshot.get("execution_context")),
                longValue(row.get("numerator_count")),
                longValue(row.get("denominator_count"))));
    }

    public Optional<SnapshotRecord> snapshotByRun(String runId) {
        List<SnapshotRecord> values = jdbc.query(
                "SELECT * FROM med_indicator_detail_snapshot WHERE run_id=?",
                (result, row) -> snapshot(result), runId);
        return values.stream().findFirst();
    }

    @Transactional
    public void beginSnapshot(
            String snapshotId,
            RunContext context,
            String relativePath,
            String createdBy,
            Instant createdAt,
            Instant expiresAt) {
        if (snapshotByRun(context.runId()).isPresent()) {
            jdbc.update("""
                    UPDATE med_indicator_detail_snapshot
                    SET snapshot_id=?, hospital_id=?, rule_id=?, relative_path=?,
                        file_sha256=NULL, denominator_count=NULL, numerator_count=NULL,
                        unmatched_count=NULL, column_schema_json=NULL, status='creating',
                        created_by=?, created_at=?, expires_at=?, error_message=NULL
                    WHERE run_id=?
                    """,
                    snapshotId, context.hospitalId(), context.ruleId(), relativePath, createdBy,
                    Timestamp.from(createdAt), Timestamp.from(expiresAt), context.runId());
            return;
        }
        jdbc.update("""
                INSERT INTO med_indicator_detail_snapshot (
                  snapshot_id, run_id, hospital_id, rule_id, relative_path,
                  status, created_by, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, 'creating', ?, ?, ?)
                """,
                snapshotId, context.runId(), context.hospitalId(), context.ruleId(),
                relativePath, createdBy, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public void markSnapshotReady(
            String runId,
            String sha256,
            int denominator,
            int numerator,
            List<DetailColumn> columns) {
        jdbc.update("""
                UPDATE med_indicator_detail_snapshot
                SET file_sha256=?, denominator_count=?, numerator_count=?, unmatched_count=?,
                    column_schema_json=?, status='ready', error_message=NULL
                WHERE run_id=?
                """,
                sha256, denominator, numerator, denominator - numerator,
                json(columns), runId);
    }

    public void markSnapshotFailed(String runId, String message) {
        jdbc.update(
                "UPDATE med_indicator_detail_snapshot SET status='failed', error_message=? WHERE run_id=?",
                limited(message), runId);
    }

    public Optional<ExportRecord> export(String exportId) {
        List<ExportRecord> values = jdbc.query(
                "SELECT * FROM med_indicator_export WHERE export_id=?",
                (result, row) -> export(result), exportId);
        return values.stream().findFirst();
    }

    public List<ExportRecord> exports(String hospitalId) {
        return jdbc.query(
                "SELECT * FROM med_indicator_export WHERE hospital_id=? ORDER BY created_at DESC",
                (result, row) -> export(result), hospitalId);
    }

    public void createExport(
            String exportId,
            SnapshotRecord snapshot,
            String relativePath,
            String fileName,
            int rowCount,
            String createdBy,
            Instant createdAt,
            Instant expiresAt) {
        jdbc.update("""
                INSERT INTO med_indicator_export (
                  export_id, snapshot_id, run_id, hospital_id, rule_id, relative_path,
                  file_name, status, row_count, created_by, created_at, expires_at, download_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'creating', ?, ?, ?, ?, 0)
                """,
                exportId, snapshot.snapshotId(), snapshot.runId(), snapshot.hospitalId(),
                snapshot.ruleId(), relativePath, fileName, rowCount, createdBy,
                Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public void markExportReady(String exportId, String sha256) {
        jdbc.update(
                "UPDATE med_indicator_export SET status='ready', file_sha256=?, error_message=NULL WHERE export_id=?",
                sha256, exportId);
    }

    public void markExportFailed(String exportId, String message) {
        jdbc.update(
                "UPDATE med_indicator_export SET status='failed', error_message=? WHERE export_id=?",
                limited(message), exportId);
    }

    public void recordDownload(String exportId, Instant downloadedAt) {
        jdbc.update("""
                UPDATE med_indicator_export
                SET download_count=download_count+1, last_downloaded_at=?
                WHERE export_id=?
                """, Timestamp.from(downloadedAt), exportId);
    }

    private SnapshotRecord snapshot(ResultSet result) throws SQLException {
        return new SnapshotRecord(
                result.getString("snapshot_id"), result.getString("run_id"),
                result.getString("hospital_id"), result.getString("rule_id"),
                result.getString("relative_path"), result.getString("file_sha256"),
                integer(result.getObject("denominator_count")), integer(result.getObject("numerator_count")),
                integer(result.getObject("unmatched_count")),
                columns(result.getString("column_schema_json")), result.getString("status"),
                result.getString("created_by"), instant(result.getObject("created_at")),
                instant(result.getObject("expires_at")), result.getString("error_message"));
    }

    private static ExportRecord export(ResultSet result) throws SQLException {
        return new ExportRecord(
                result.getString("export_id"), result.getString("snapshot_id"),
                result.getString("run_id"), result.getString("hospital_id"),
                result.getString("rule_id"), result.getString("relative_path"),
                result.getString("file_name"), result.getString("file_sha256"),
                result.getString("status"), result.getInt("row_count"),
                result.getString("created_by"), instant(result.getObject("created_at")),
                instant(result.getObject("expires_at")), result.getInt("download_count"),
                result.getString("error_message"));
    }

    private List<DetailColumn> columns(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<DetailColumn>>() {});
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("明细对象序列化失败", exception);
        }
    }

    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return objectMap(raw);
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(String.valueOf(value),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> rowMap(ResultSet result, int row) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            int type = metadata.getColumnType(index);
            Object value = type == Types.CLOB || type == Types.NCLOB
                    || type == Types.LONGVARCHAR || type == Types.LONGNVARCHAR
                    ? result.getString(index)
                    : result.getObject(index);
            values.put(metadata.getColumnLabel(index).toLowerCase(), value);
        }
        return values;
    }

    private static String sqlTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(SQL_TIME);
        }
        if (value instanceof LocalDateTime time) {
            return time.format(SQL_TIME);
        }
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(number.longValue()), ZoneOffset.UTC).format(SQL_TIME);
        }
        return text(value);
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
        if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
        String raw = text(value);
        if (raw.isBlank()) return null;
        if (raw.matches("-?\\d+")) return Instant.ofEpochMilli(Long.parseLong(raw));
        try {
            return Instant.parse(raw);
        } catch (java.time.format.DateTimeParseException ignored) {
            return Timestamp.valueOf(raw).toInstant();
        }
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String nullableText(Object value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long longValue(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String limited(String value) {
        String text = value == null ? "" : value;
        return text.length() > 1_000 ? text.substring(0, 1_000) : text;
    }

    public record SnapshotRecord(
            String snapshotId,
            String runId,
            String hospitalId,
            String ruleId,
            String relativePath,
            String sha256,
            Integer denominatorCount,
            Integer numeratorCount,
            Integer unmatchedCount,
            List<DetailColumn> columns,
            String status,
            String createdBy,
            Instant createdAt,
            Instant expiresAt,
            String errorMessage) {
    }

    public record ExportRecord(
            String exportId,
            String snapshotId,
            String runId,
            String hospitalId,
            String ruleId,
            String relativePath,
            String fileName,
            String sha256,
            String status,
            int rowCount,
            String createdBy,
            Instant createdAt,
            Instant expiresAt,
            int downloadCount,
            String errorMessage) {
    }
}

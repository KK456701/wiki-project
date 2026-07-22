package com.hospital.wikiagent.metadata;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 封装 {@code MetadataRepository} 对应数据的持久化与查询，避免上层依赖具体存储实现。
 *
 * <p>所有存储语句、JSON 转换和对象有效期检查集中在此处，调用方只传递类型化条件。实现不得绕过医院隔离，也不得把患者级明细写入日志或通用 Trace。</p>
 */
@Repository
public class MetadataRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MetadataRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Snapshot loadCurrent(String hospitalId, String databaseName) {
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT table_name,table_comment,table_type FROM med_metadata_table "
                        + "WHERE hospital_id=? AND db_name=? ORDER BY table_name",
                hospitalId, databaseName);
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT table_name,column_name,data_type,column_type,is_nullable,column_key,"
                        + "column_default,column_comment FROM med_metadata_column "
                        + "WHERE hospital_id=? AND db_name=? ORDER BY table_name,column_name",
                hospitalId, databaseName);
        return new Snapshot(normalizeRows(tables), normalizeRows(columns));
    }

    public List<FieldMapping> fieldMappings(
            String hospitalId, String sourceId, String databaseName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rule_id,business_field,db_name,table_name,column_name "
                        + "FROM med_field_mapping WHERE hospital_id=? AND status='confirmed' "
                        + "ORDER BY rule_id,table_name,column_name",
                hospitalId);
        return rows.stream().map(MetadataRepository::normalizeRow)
                .filter(row -> matchesDatabase(text(row.get("db_name")), sourceId, databaseName))
                .map(row -> new FieldMapping(
                        text(row.get("rule_id")), text(row.get("business_field")),
                        text(row.get("table_name")), text(row.get("column_name"))))
                .toList();
    }

    public Map<String, Object> overview(
            String hospitalId, String databaseName, String sourceId) {
        List<Map<String, Object>> snapshots = jdbc.queryForList(
                "SELECT metadata_source,sync_batch_id,snapshot_json,created_at "
                        + "FROM med_metadata_snapshot WHERE hospital_id=? AND db_name=? "
                        + "ORDER BY id DESC LIMIT 1",
                hospitalId, databaseName);
        if (snapshots.isEmpty()) {
            return emptyOverview(hospitalId, databaseName, sourceId);
        }
        Map<String, Object> snapshotRow = normalizeRow(snapshots.get(0));
        String batchId = text(snapshotRow.get("sync_batch_id"));
        Map<String, Object> snapshot = jsonObject(snapshotRow.get("snapshot_json"));
        List<Map<String, Object>> changes = normalizeRows(jdbc.queryForList(
                "SELECT table_name,field_name,change_type,change_desc "
                        + "FROM med_metadata_sync_log WHERE hospital_id=? AND db_name=? "
                        + "AND sync_batch_id=? AND change_type<>'full_sync' "
                        + "ORDER BY table_name,field_name,change_type",
                hospitalId, databaseName, batchId));
        List<FieldMapping> mappings = fieldMappings(hospitalId, sourceId, databaseName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hospital_id", hospitalId);
        result.put("db_name", databaseName);
        result.put("has_snapshot", true);
        result.put("metadata_source", snapshotRow.get("metadata_source"));
        result.put("batch_id", batchId);
        result.put("synced_at", iso(snapshotRow.get("created_at")));
        result.put("table_count", list(snapshot.get("tables")).size());
        result.put("column_count", list(snapshot.get("columns")).size());
        result.put("changes", changes);
        result.put("affected_rules", affectedRules(changes, mappings));
        return result;
    }

    @Transactional
    public void persist(
            String hospitalId,
            String databaseName,
            String source,
            String batchId,
            Snapshot snapshot,
            List<Map<String, Object>> changes) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                    "tables", snapshot.tables(), "columns", snapshot.columns()));
        } catch (Exception exception) {
            throw new IllegalStateException("元数据快照序列化失败。", exception);
        }
        jdbc.update("INSERT INTO med_metadata_snapshot "
                        + "(hospital_id,db_name,metadata_source,sync_batch_id,snapshot_json,created_at) "
                        + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                hospitalId, databaseName, source, batchId, json);

        jdbc.update("DELETE FROM med_metadata_column WHERE hospital_id=? AND db_name=?",
                hospitalId, databaseName);
        jdbc.update("DELETE FROM med_metadata_table WHERE hospital_id=? AND db_name=?",
                hospitalId, databaseName);

        List<Object[]> tableParams = new ArrayList<>();
        for (Map<String, Object> table : snapshot.tables()) {
            tableParams.add(new Object[]{hospitalId, databaseName, text(table.get("table_name")),
                    text(table.get("table_comment")), text(table.get("table_type")), batchId});
        }
        if (!tableParams.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO med_metadata_table "
                    + "(hospital_id,db_name,table_name,table_comment,table_type,sync_batch_id,sync_time) "
                    + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)", tableParams);
        }

        List<Object[]> columnParams = new ArrayList<>();
        for (Map<String, Object> column : snapshot.columns()) {
            columnParams.add(new Object[]{hospitalId, databaseName, text(column.get("table_name")),
                    text(column.get("column_name")), text(column.get("data_type")),
                    text(column.get("column_type")), text(column.get("is_nullable")),
                    text(column.get("column_key")), text(column.get("column_default")),
                    text(column.get("column_comment")), batchId});
        }
        if (!columnParams.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO med_metadata_column "
                    + "(hospital_id,db_name,table_name,column_name,data_type,column_type,is_nullable,"
                    + "column_key,column_default,column_comment,sync_batch_id,sync_time) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", columnParams);
        }

        List<Map<String, Object>> logItems = new ArrayList<>();
        logItems.add(Map.of(
                "table_name", "", "field_name", "", "change_type", "full_sync",
                "change_desc", "元数据同步完成: " + snapshot.tables().size()
                        + " 张表, " + snapshot.columns().size() + " 个字段"));
        logItems.addAll(changes);
        List<Object[]> logParams = logItems.stream().map(change -> new Object[]{
                hospitalId, databaseName, text(change.get("table_name")),
                text(change.get("field_name")), text(change.get("change_type")),
                text(change.get("change_desc")), batchId}).toList();
        jdbc.batchUpdate("INSERT INTO med_metadata_sync_log "
                + "(hospital_id,db_name,table_name,field_name,change_type,change_desc,sync_batch_id,sync_time) "
                + "VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", logParams);
    }

    public static List<Map<String, Object>> affectedRules(
            List<Map<String, Object>> changes, List<FieldMapping> mappings) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map<String, Object> item : changes) {
            changed.add(key(text(item.get("table_name")), text(item.get("field_name"))));
        }
        Map<String, LinkedHashSet<String>> columns = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> fields = new LinkedHashMap<>();
        for (FieldMapping mapping : mappings) {
            if (!changed.contains(key(mapping.tableName(), mapping.columnName()))
                    && !changed.contains(key(mapping.tableName(), ""))) {
                continue;
            }
            columns.computeIfAbsent(mapping.ruleId(), ignored -> new LinkedHashSet<>())
                    .add(mapping.columnName());
            fields.computeIfAbsent(mapping.ruleId(), ignored -> new LinkedHashSet<>())
                    .add(mapping.businessField());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String ruleId : columns.keySet()) {
            result.add(Map.of(
                    "rule_id", ruleId,
                    "matched_columns", List.copyOf(columns.get(ruleId)),
                    "business_fields", List.copyOf(fields.get(ruleId))));
        }
        return result;
    }

    private Map<String, Object> emptyOverview(
            String hospitalId, String databaseName, String sourceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hospital_id", hospitalId);
        result.put("db_name", databaseName);
        result.put("source_id", sourceId);
        result.put("has_snapshot", false);
        result.put("metadata_source", null);
        result.put("batch_id", null);
        result.put("synced_at", null);
        result.put("table_count", 0);
        result.put("column_count", 0);
        result.put("changes", List.of());
        result.put("affected_rules", List.of());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonObject(Object value) {
        try {
            if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
            return objectMapper.readValue(String.valueOf(value), Map.class);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private static boolean matchesDatabase(String value, String sourceId, String databaseName) {
        return value != null && (value.equalsIgnoreCase(sourceId) || value.equalsIgnoreCase(databaseName));
    }

    private static String key(String table, String column) {
        return (table == null ? "" : table.toLowerCase(Locale.ROOT)) + "."
                + (column == null ? "" : column.toLowerCase(Locale.ROOT));
    }

    private static List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(MetadataRepository::normalizeRow).toList();
    }

    private static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> values ? (List<Object>) values : List.of();
    }

    private static String iso(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof LocalDateTime time) return time.toString();
        return value == null ? null : String.valueOf(value);
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Snapshot(List<Map<String, Object>> tables, List<Map<String, Object>> columns) {
        public Snapshot {
            tables = List.copyOf(tables);
            columns = List.copyOf(columns);
        }
    }

    public record FieldMapping(
            String ruleId, String businessField, String tableName, String columnName) {
    }
}

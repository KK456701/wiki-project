package com.hospital.wikiagent.metadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hospital.wikiagent.agent.trace.AgentTraceService;
import com.hospital.wikiagent.auth.HospitalPrincipal;
import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.metadata.MetadataRepository.FieldMapping;
import com.hospital.wikiagent.metadata.MetadataRepository.Snapshot;

/**
 * 编排 {@code MetadataSyncService} 对应的业务流程，并集中维护事务与安全边界。
 *
 * <p>该服务负责按业务顺序组合依赖，并把可预期失败转换为稳定错误语义。它不允许模型直接访问数据库，也不允许上层绕过策略、Evidence 或医院隔离边界。</p>
 */
@Service
public class MetadataSyncService {
    private final MetadataCatalogClient catalog;
    private final MetadataRepository repository;
    private final DbHubProperties properties;
    private final AgentTraceService traces;

    public MetadataSyncService(
            MetadataCatalogClient catalog,
            MetadataRepository repository,
            DbHubProperties properties,
            AgentTraceService traces) {
        this.catalog = catalog;
        this.repository = repository;
        this.properties = properties;
        this.traces = traces;
    }

    public Map<String, Object> overview(HospitalPrincipal principal, String requestedDatabase) {
        String databaseName = database(requestedDatabase);
        return repository.overview(
                principal.hospitalId(), databaseName, properties.getSourceId());
    }

    public Map<String, Object> sync(
            HospitalPrincipal principal,
            String requestedHospitalId,
            String requestedDatabase,
            String source) {
        if (requestedHospitalId != null && !requestedHospitalId.isBlank()
                && !principal.canAccessHospital(requestedHospitalId)) {
            throw new IllegalArgumentException("不能同步其他医院的数据库元数据。");
        }
        if (source != null && !source.isBlank() && !"dbhub".equalsIgnoreCase(source)) {
            throw new IllegalArgumentException("Java 主链路只允许通过 DBHub 同步业务库元数据。");
        }
        String databaseName = database(requestedDatabase);
        String traceId = id("TRACE_");
        String batchId = id("META_");
        traces.start(traceId, null, principal, "metadata_sync:" + databaseName);
        long started = System.currentTimeMillis();
        try {
            List<FieldMapping> mappings = repository.fieldMappings(
                    principal.hospitalId(), properties.getSourceId(), databaseName);
            Snapshot previous = mappedPrevious(
                    repository.loadCurrent(principal.hospitalId(), databaseName), mappings);
            Snapshot current = collect(databaseName, mappings);
            List<Map<String, Object>> changes = diff(previous, current);
            repository.persist(
                    principal.hospitalId(), databaseName, catalog.sourceName(), batchId,
                    current, changes);
            List<Map<String, Object>> affected = MetadataRepository.affectedRules(changes, mappings);
            long duration = Math.max(0, System.currentTimeMillis() - started);
            traces.recordStandaloneNode(traceId, node(
                    "metadata_sync_dbhub", "database", "success", started, duration,
                    Map.of("source_id", properties.getSourceId(), "database", databaseName,
                            "mapped_table_count", mappedTables(mappings).size()),
                    Map.of("batch_id", batchId, "table_count", current.tables().size(),
                            "column_count", current.columns().size(), "change_count", changes.size())));
            Map<String, Object> result = response(
                    principal.hospitalId(), databaseName, batchId, current, changes, affected, traceId);
            traces.finishStandalone(traceId, "success", "metadata_sync",
                    "元数据同步完成。", 0);
            return result;
        } catch (RuntimeException exception) {
            long duration = Math.max(0, System.currentTimeMillis() - started);
            traces.recordStandaloneNode(traceId, node(
                    "metadata_sync_dbhub", "database", "failed", started, duration,
                    Map.of("source_id", properties.getSourceId(), "database", databaseName),
                    Map.of("error", safeMessage(exception))));
            traces.finishStandalone(traceId, "failed", "metadata_sync",
                    safeMessage(exception), 1);
            throw exception;
        }
    }

    private Snapshot collect(String databaseName, List<FieldMapping> mappings) {
        List<Map<String, Object>> rawTables = catalog.listTables(
                databaseName, properties.getSchemaName());
        Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
        for (Map<String, Object> raw : rawTables) {
            Map<String, Object> table = normalize(raw);
            String name = text(table.get("table_name"));
            if (name.isBlank()) continue;
            table.putIfAbsent("table_comment", "");
            table.putIfAbsent("table_type", "");
            tables.put(name.toLowerCase(Locale.ROOT), table);
        }

        Map<String, Map<String, Object>> columns = new LinkedHashMap<>();
        for (String tableName : mappedTables(mappings)) {
            tables.putIfAbsent(tableName.toLowerCase(Locale.ROOT), mappedTable(tableName));
            for (Map<String, Object> raw : catalog.listColumns(
                    databaseName, properties.getSchemaName(), tableName)) {
                Map<String, Object> column = normalize(raw);
                String returnedTable = first(text(column.get("table_name")), tableName);
                String columnName = text(column.get("column_name"));
                if (columnName.isBlank()) continue;
                column.put("table_name", returnedTable);
                for (String field : List.of(
                        "data_type", "column_type", "is_nullable", "column_key",
                        "column_default", "column_comment")) {
                    column.putIfAbsent(field, "");
                }
                columns.put(key(returnedTable, columnName), column);
            }
        }
        return new Snapshot(
                tables.values().stream().sorted(Comparator.comparing(
                        value -> text(value.get("table_name")), String.CASE_INSENSITIVE_ORDER)).toList(),
                columns.values().stream().sorted(Comparator.comparing(
                        value -> key(text(value.get("table_name")), text(value.get("column_name"))))).toList());
    }

    static List<Map<String, Object>> diff(Snapshot previous, Snapshot current) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Map<String, Object>> oldTables = index(previous.tables(), "table_name", null);
        Map<String, Map<String, Object>> newTables = index(current.tables(), "table_name", null);
        for (String value : sortedDifference(newTables.keySet(), oldTables.keySet())) {
            String table = text(newTables.get(value).get("table_name"));
            changes.add(change("table_added", table, "", "新增表: " + table));
        }
        for (String value : sortedDifference(oldTables.keySet(), newTables.keySet())) {
            String table = text(oldTables.get(value).get("table_name"));
            changes.add(change("table_deleted", table, "", "删除表: " + table));
        }

        Map<String, Map<String, Object>> oldColumns = index(previous.columns(), "table_name", "column_name");
        Map<String, Map<String, Object>> newColumns = index(current.columns(), "table_name", "column_name");
        for (String value : sortedDifference(newColumns.keySet(), oldColumns.keySet())) {
            Map<String, Object> column = newColumns.get(value);
            String table = text(column.get("table_name"));
            String name = text(column.get("column_name"));
            changes.add(change("column_added", table, name, "新增字段: " + table + "." + name));
        }
        for (String value : sortedDifference(oldColumns.keySet(), newColumns.keySet())) {
            Map<String, Object> column = oldColumns.get(value);
            String table = text(column.get("table_name"));
            String name = text(column.get("column_name"));
            changes.add(change("column_deleted", table, name, "删除字段: " + table + "." + name));
        }
        Set<String> common = new LinkedHashSet<>(oldColumns.keySet());
        common.retainAll(newColumns.keySet());
        common.stream().sorted().forEach(value -> {
            Map<String, Object> oldColumn = oldColumns.get(value);
            Map<String, Object> newColumn = newColumns.get(value);
            String table = text(newColumn.get("table_name"));
            String name = text(newColumn.get("column_name"));
            String oldType = first(text(oldColumn.get("column_type")), text(oldColumn.get("data_type")));
            String newType = first(text(newColumn.get("column_type")), text(newColumn.get("data_type")));
            if (!oldType.equalsIgnoreCase(newType)
                    || !text(oldColumn.get("data_type")).equalsIgnoreCase(text(newColumn.get("data_type")))) {
                changes.add(change("column_type_changed", table, name,
                        "字段类型变化: " + table + "." + name + " " + oldType + " -> " + newType));
            }
            String oldNullable = text(oldColumn.get("is_nullable")).toUpperCase(Locale.ROOT);
            String newNullable = text(newColumn.get("is_nullable")).toUpperCase(Locale.ROOT);
            if (!oldNullable.equals(newNullable)) {
                changes.add(change("column_nullable_changed", table, name,
                        "字段可空性变化: " + table + "." + name + " "
                                + oldNullable + " -> " + newNullable));
            }
        });
        return List.copyOf(changes);
    }

    private Snapshot mappedPrevious(Snapshot previous, List<FieldMapping> mappings) {
        Set<String> tables = mappedTables(mappings).stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(
                        java.util.stream.Collectors.toSet());
        return new Snapshot(previous.tables(), previous.columns().stream()
                .filter(value -> tables.contains(text(value.get("table_name")).toLowerCase(Locale.ROOT)))
                .toList());
    }

    private String database(String requested) {
        if (requested == null || requested.isBlank()
                || requested.equalsIgnoreCase(properties.getSourceId())
                || requested.equalsIgnoreCase(properties.getDatabaseName())) {
            return properties.getDatabaseName();
        }
        throw new IllegalArgumentException("当前迁移版只允许同步已配置的 DBHub 业务库。");
    }

    private static List<String> mappedTables(List<FieldMapping> mappings) {
        Map<String, String> values = new LinkedHashMap<>();
        for (FieldMapping mapping : mappings) {
            if (mapping.tableName() != null && !mapping.tableName().isBlank()) {
                values.putIfAbsent(mapping.tableName().toLowerCase(Locale.ROOT), mapping.tableName());
            }
        }
        return List.copyOf(values.values());
    }

    private Map<String, Object> response(
            String hospitalId, String databaseName, String batchId, Snapshot snapshot,
            List<Map<String, Object>> changes, List<Map<String, Object>> affected,
            String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hospital_id", hospitalId);
        result.put("db_name", databaseName);
        result.put("source_id", properties.getSourceId());
        result.put("has_snapshot", true);
        result.put("metadata_source", catalog.sourceName());
        result.put("synced_at", java.time.LocalDateTime.now().toString());
        result.put("table_count", snapshot.tables().size());
        result.put("column_count", snapshot.columns().size());
        result.put("batch_id", batchId);
        result.put("changes", changes);
        result.put("affected_rules", affected);
        result.put("trace_id", traceId);
        return result;
    }

    private static Map<String, Object> node(
            String name, String type, String status, long started, long duration,
            Map<String, Object> input, Map<String, Object> output) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "trace_node");
        event.put("node_name", name);
        event.put("node_type", type);
        event.put("status", status);
        event.put("started_at_epoch_ms", started);
        event.put("ended_at_epoch_ms", started + duration);
        event.put("duration_ms", duration);
        event.put("tool_name", "dbhub_metadata_sync");
        event.put("db_source", "dbhub");
        event.put("capability", "sync_database_metadata");
        event.put("input", input);
        event.put("output", output);
        return event;
    }

    private static Map<String, Map<String, Object>> index(
            List<Map<String, Object>> rows, String first, String second) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String value = text(row.get(first));
            if (value.isBlank()) continue;
            String itemKey = second == null ? value.toLowerCase(Locale.ROOT)
                    : key(value, text(row.get(second)));
            result.put(itemKey, row);
        }
        return result;
    }

    private static List<String> sortedDifference(Set<String> left, Set<String> right) {
        return left.stream().filter(value -> !right.contains(value)).sorted().toList();
    }

    private static Map<String, Object> mappedTable(String tableName) {
        return new LinkedHashMap<>(Map.of(
                "table_name", tableName,
                "table_comment", "指标映射依赖表",
                "table_type", "MAPPED_OBJECT"));
    }

    private static Map<String, Object> change(
            String type, String table, String field, String description) {
        return new LinkedHashMap<>(Map.of(
                "change_type", type, "table_name", table,
                "field_name", field, "change_desc", description));
    }

    private static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static String key(String table, String column) {
        return text(table).toLowerCase(Locale.ROOT) + "." + text(column).toLowerCase(Locale.ROOT);
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? "元数据同步失败。" : value;
    }
}

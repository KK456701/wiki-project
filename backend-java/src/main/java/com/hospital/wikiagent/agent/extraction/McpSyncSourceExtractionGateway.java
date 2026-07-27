package com.hospital.wikiagent.agent.extraction;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.StrUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dbhub.DbHubMcpException;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;

/**
 * 通过同事的业务 MCP（execCustomQuery）抽取数据并写入 winex_aima。
 *
 * <p>替换原 BusinessMcpSourceExtractionGateway（走本地 DBHub MCP），改为调用
 * {@code biz-mcp-url} 配置的远程 MCP 服务。读取路径走同事的 MCP，写入路径仍然
 * 使用本地 SQL Server JDBC 直连。</p>
 */
@Component
@ConditionalOnProperty(prefix = "wiki.agent.extraction", name = "mode", havingValue = "required")
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class McpSyncSourceExtractionGateway implements SourceExtractionGateway {

    private static final Logger log = LoggerFactory.getLogger(McpSyncSourceExtractionGateway.class);
    private static final String TOOLS_NAME = "execCustomQuery";
    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int SQL_SERVER_MAX_PARAMS = 2000;
    private static final int DEFAULT_INSERT_BATCH_ROWS = 200;
    private static final int ENCOUNTER_ID_BATCH_SIZE = 500;

    private final Snowflake snowflake = new Snowflake(1, 1);

    /** 患者维度表：按 ENCOUNTER_ID 过滤抽取。 */
    private static final Set<String> PATIENT_TABLES = Set.of(
            "INP_CLI_ORDER", "INP_SURGICAL_ANESTHESIA_PLAN",
            "INPAT_TRANSFER", "INPATIENT_ENCOUNTER",
            "MRAS_INDEX_SURGREC", "MRAS_PATIENT_EVENT");

    private final DbHubMcpClient dbHubMcpClient;
    private final SqlServerProperties sqlServerProperties;
    private final JdbcTemplate sqlServerJdbcTemplate;

    public McpSyncSourceExtractionGateway(
            DbHubMcpClient dbHubMcpClient,
            SqlServerProperties sqlServerProperties,
            @Qualifier("sqlServerJdbcTemplate") JdbcTemplate sqlServerJdbcTemplate) {
        this.dbHubMcpClient = dbHubMcpClient;
        this.sqlServerProperties = sqlServerProperties;
        this.sqlServerJdbcTemplate = sqlServerJdbcTemplate;
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
        try {
            if (request.hospitalSoid() == null) {
                return SourceExtractionLease.completed(failed(
                        extractionId, "EXTRACTION_HOSPITAL_SOID_MISSING",
                        "未配置业务 MCP 所需的医院 SOID。"));
            }

            Map<String, Object> contract = request.extractionContract();
            String eventTable = text(contract.get("event_table"));
            List<String> dependencyTables = strings(contract.get("dependency_tables"));

            String start = SQL_TIME.format(request.statStart());
            String end = SQL_TIME.format(request.statEnd());

            // ---- 第一步：抽取事件中间表 ----
            long totalExtracted = 0;
            long totalInserted = 0;

            String sourceSql = text(request.sourceSql());
            if (!sourceSql.isBlank() && !eventTable.isBlank()) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("startTime", start);
                params.put("endTime", end);

                log.info("[{}] 开始抽取事件表 {} ({} ~ {})", extractionId, eventTable, start, end);
                List<Map<String, Object>> eventRows = callMcp(sourceSql, params, request.hospitalSoid());
                totalExtracted += eventRows.size();

                replaceTableData(eventTable, eventRows);
                totalInserted += eventRows.size();
                log.info("[{}] 事件表 {} 写入完成: {} 行", extractionId, eventTable, eventRows.size());

                // ---- 第二步：收集 ENCOUNTER_ID ----
                Set<String> encounterIds = collectEncounterIds(eventRows);

                // ---- 第三步：抽取依赖表 ----
                for (String depTable : dependencyTables) {
                    if (depTable.equalsIgnoreCase(eventTable)) {
                        continue; // 事件表已处理
                    }
                    List<Map<String, Object>> depRows = fetchDependencyTable(
                            depTable, encounterIds, request.hospitalSoid());
                    totalExtracted += depRows.size();

                    replaceTableData(depTable, depRows);
                    totalInserted += depRows.size();
                    log.info("[{}] 依赖表 {} 写入完成: {} 行", extractionId, depTable, depRows.size());
                }
            }

            ExtractionResult result = new ExtractionResult(
                    extractionId,
                    ExtractionResult.Status.SUCCESS,
                    totalExtracted, totalInserted, 0, 0,
                    Instant.now(),
                    request.idempotencyKey(),
                    "SNAP_" + UUID.randomUUID().toString().replace("-", ""),
                    "",
                    "已通过业务 MCP 刷新真实库快照。");
            return SourceExtractionLease.completed(result);

        } catch (Exception exception) {
            log.error("[{}] 抽取失败: {}", extractionId, exception.getMessage(), exception);
            return SourceExtractionLease.completed(failed(
                    extractionId,
                    errorCode(exception),
                    safeMessage(exception)));
        }
    }

    // ==================== MCP 调用 ====================

    private List<Map<String, Object>> callMcp(
            String sqlScript, Map<String, Object> params, Long hospitalSOID) {
        var result = dbHubMcpClient.callTool(TOOLS_NAME, sqlScript, params, hospitalSOID);
        List<Map<String, Object>> rows = DbHubMcpClient.extractRowsV2(result);
        return rows == null ? List.of() : rows;
    }

    private List<Map<String, Object>> fetchDependencyTable(
            String table, Set<String> encounterIds, Long hospitalSOID) {
        if (PATIENT_TABLES.contains(table.toUpperCase(Locale.ROOT))) {
            if (encounterIds.isEmpty()) {
                log.info("依赖表 {} 是患者表但无 ENCOUNTER_ID，跳过", table);
                return List.of();
            }
            List<String> ids = new ArrayList<>(encounterIds);
            List<Map<String, Object>> allRows = new ArrayList<>();
            for (int offset = 0; offset < ids.size(); offset += ENCOUNTER_ID_BATCH_SIZE) {
                List<String> batch = ids.subList(
                        offset, Math.min(offset + ENCOUNTER_ID_BATCH_SIZE, ids.size()));
                String inClause = batch.stream()
                        .map(this::numericId)
                        .collect(Collectors.joining(","));
                String sql = "SELECT * FROM " + table
                        + " WHERE ENCOUNTER_ID IN (" + inClause + ")";
                allRows.addAll(callMcp(sql, Map.of(), hospitalSOID));
            }
            return allRows;
        }
        // 基础表：全量抽取
        String sql = "SELECT * FROM " + table + " WHERE IS_DEL = 0";
        try {
            return callMcp(sql, Map.of(), hospitalSOID);
        } catch (Exception e) {
            // IS_DEL 列可能不存在，回退到全量
            log.warn("表 {} 使用 IS_DEL 过滤失败，回退全量抽取: {}", table, e.getMessage());
            return callMcp("SELECT * FROM " + table, Map.of(), hospitalSOID);
        }
    }

    // ==================== 数据写入（逻辑同 SyncDataService） ====================

    private void replaceTableData(String tableName, List<Map<String, Object>> rows) {
        String qualified = qualifyTable(tableName);
        sqlServerJdbcTemplate.update("DELETE FROM " + qualified);
        log.info("已清空表 {}", qualified);

        if (rows == null || rows.isEmpty()) {
            return;
        }
        // 生成主键列（表名_ID），用 Snowflake 填充（同 SyncDataService 逻辑）
        String pkColumn = tableName.toUpperCase(Locale.ROOT) + "_ID";
        List<Map<String, Object>> converted = rows.stream()
                .map(row -> {
                    Map<String, Object> newRow = convertKeysToUpperSnakeCase(row);
                    newRow.put(pkColumn, snowflake.nextId());
                    return newRow;
                })
                .collect(Collectors.toList());
        insertRows(tableName, converted);
    }

    /** 按目标表实际列插入（同 SyncDataService.insertRows）。 */
    private void insertRows(String tableName, List<Map<String, Object>> rows) {
        List<String> columns = resolveLocalColumns(tableName);
        if (columns.isEmpty()) {
            log.warn("表 {} 无法解析列，跳过插入", tableName);
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }

        // 将每行规范化为目标表全列（缺失的填 null → DEFAULT）
        List<Map<String, Object>> normalizedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            Map<String, Object> upperRow = new HashMap<>(row.size());
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null) {
                    upperRow.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
                }
            }
            Map<String, Object> fullRow = new LinkedHashMap<>(columns.size());
            for (String column : columns) {
                fullRow.put(column, upperRow.get(column));
            }
            normalizedRows.add(fullRow);
        }
        if (normalizedRows.isEmpty()) {
            return;
        }
        batchInsert(qualifyTable(tableName), columns, normalizedRows);
        log.info("插入 {} 行到 {}", normalizedRows.size(), tableName);
    }

    /** 批量 INSERT，null 值用 DEFAULT（同 SyncDataService.batchInsert）。 */
    private void batchInsert(String qualifiedTable, List<String> columns, List<Map<String, Object>> rows) {
        int maxRowsPerBatch = Math.max(1, Math.min(
                DEFAULT_INSERT_BATCH_ROWS,
                SQL_SERVER_MAX_PARAMS / columns.size()));
        String columnList = String.join(",", columns);

        for (int from = 0; from < rows.size(); from += maxRowsPerBatch) {
            int to = Math.min(from + maxRowsPerBatch, rows.size());
            List<Map<String, Object>> batch = rows.subList(from, to);

            List<Object> params = new ArrayList<>(batch.size() * columns.size());
            List<String> valueGroups = new ArrayList<>(batch.size());
            for (Map<String, Object> row : batch) {
                List<String> placeholders = new ArrayList<>(columns.size());
                for (String column : columns) {
                    Object value = row.get(column);
                    if (value == null) {
                        placeholders.add("DEFAULT");
                    } else {
                        placeholders.add("?");
                        params.add(value);
                    }
                }
                valueGroups.add("(" + String.join(",", placeholders) + ")");
            }

            String sql = "INSERT INTO " + qualifiedTable + " (" + columnList + ") VALUES "
                    + String.join(",", valueGroups);
            sqlServerJdbcTemplate.update(sql, params.toArray());
        }
    }

    /** 解析目标表实际列名（大写，按序号排列），带缓存。 */
    private final Map<String, List<String>> localColumnCache = new HashMap<>();

    private List<String> resolveLocalColumns(String tableName) {
        String cacheKey = tableName == null ? "" : tableName.toUpperCase(Locale.ROOT);
        if (localColumnCache.containsKey(cacheKey)) {
            return localColumnCache.get(cacheKey);
        }
        List<String> columns = new ArrayList<>();
        try {
            String schema = resolveSchema();
            List<String> names = sqlServerJdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
                    String.class, schema, tableName);
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    columns.add(name.toUpperCase(Locale.ROOT));
                }
            }
        } catch (Exception e) {
            log.warn("解析表 {} 列失败: {}", tableName, e.getMessage());
        }
        localColumnCache.put(cacheKey, columns);
        return columns;
    }

    private String resolveSchema() {
        String schema = sqlServerProperties.getSchema();
        return (schema == null || schema.isBlank()) ? "dbo" : schema;
    }

    // ==================== 辅助方法 ====================

    private Map<String, Object> convertKeysToUpperSnakeCase(Map<String, Object> row) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            String newKey = (key == null || key.isBlank()) ? key
                    : StrUtil.toUnderlineCase(key).toUpperCase(Locale.ROOT);
            converted.put(newKey, entry.getValue());
        }
        return converted;
    }

    private Set<String> collectEncounterIds(List<Map<String, Object>> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object id = valueIgnoreCase(row, "ENCOUNTER_ID");
            if (id != null && !String.valueOf(id).isBlank()) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    private String numericId(String id) {
        try {
            return String.valueOf(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return "'" + id.replace("'", "''") + "'";
        }
    }

    private String qualifyTable(String tableName) {
        return resolveSchema() + "." + tableName;
    }

    private static Object valueIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static ExtractionResult failed(String extractionId, String code, String message) {
        return new ExtractionResult(
                extractionId, ExtractionResult.Status.FAILED,
                0, 0, 0, 0, Instant.now(),
                "", "", code, message);
    }

    private static String errorCode(Exception exception) {
        if (exception instanceof DbHubMcpException) {
            return "MCP_CALL_FAILED";
        }
        return "SOURCE_EXTRACTION_FAILED";
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "源数据抽取失败。";
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }
}

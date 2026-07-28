package com.hospital.wikiagent.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.StrUtil;
import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dto.SyncDataDto;
import com.hospital.wikiagent.dto.TableDataDto;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 核心制度指标数据同步服务：通过业务 MCP 抽取源数据并写入本地 SQL Server。
 *
 * <p>职责边界：仅负责数据搬运与格式转换，不涉及指标计算逻辑；
 * 写入操作通过 Spring 事务管理，失败时整体回滚以保证一致性。</p>
 */
// @Service — 逻辑已复制到 McpSyncSourceExtractionGateway，本类保留作参考
public class SyncDataService {

    private static final Logger log = LoggerFactory.getLogger(SyncDataService.class);
    private static final DateTimeFormatter SQL_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Keep under SQL Server 2100-parameter limit with some headroom. */
    private static final int SQL_SERVER_MAX_PARAMS = 2000;
    private static final int DEFAULT_INSERT_BATCH_ROWS = 200;

    private static final String TOOLS_NAME = "execCustomQuery";

    private final Snowflake snowflake = new Snowflake(1, 1);

    private final DbHubMcpClient dbHubMcpClient;
    private final SqlServerProperties sqlServerProperties;
    private final JdbcTemplate sqlServerJdbcTemplate;

    @PersistenceContext(unitName = "sqlServer")
    private EntityManager entityManager;

    /** Local table column cache: key=table name upper, value=local column names upper in ordinal order */
    private final Map<String, List<String>> localColumnCache = new HashMap<>();

    // base tables
    private final List<String> baseTableList = new ArrayList<>() {{
        add("BUSINESS_UNIT_X_BU_TYPE");
        add("MRAS_MEDTECH_PRO");
        add("MRAS_MEDTECH_PROC");
        add("CLIBASIC_SURGERY");
    }};

    // patient tables
    private final List<String> patableList = new ArrayList<>() {{
        add("INP_CLI_ORDER");
        add("INP_SURGICAL_ANESTHESIA_PLAN");
        add("INPAT_TRANSFER");
        add("INPATIENT_ENCOUNTER");
        add("MRAS_INDEX_SURGREC");
        add("MRAS_PATIENT_EVENT");
    }};

    public SyncDataService(DbHubMcpClient dbHubMcpClient,
                           SqlServerProperties sqlServerProperties,
                           @Qualifier("sqlServerJdbcTemplate") JdbcTemplate sqlServerJdbcTemplate) {
        this.dbHubMcpClient = dbHubMcpClient;
        this.sqlServerProperties = sqlServerProperties;
        this.sqlServerJdbcTemplate = sqlServerJdbcTemplate;
    }

    @Transactional("sqlServerTransactionManager")
    public String syncEventData(SyncDataDto syncDataDto) {
        List<TableDataDto> eventDataList = syncDataDto.getEventDataList();
        if (eventDataList == null || eventDataList.isEmpty()) {
            return "success";
        }

        //MRAS_PATIENT_EVENT患者事件表数据同步
        List<TableDataDto> eventTableList = syncDataDto.getEventTableList();

        // 当eventTableList非空时，执行sqlScript抽取数据到SqlServer
        if (CollUtil.isNotEmpty(eventTableList)) {
            for (TableDataDto eventTableDto : eventTableList) {
                String eventNo = eventTableDto.getEventNo();
                String targetTable = eventTableDto.getTable();
                String sqlScript = eventTableDto.getSqlScript();

                if (StrUtil.isBlank(targetTable) || StrUtil.isBlank(sqlScript)) {
                    log.warn("eventTableList item has blank table or sqlScript, skip");
                    continue;
                }

                log.info("start execute sqlScript for table {}", targetTable);
                try {
                    Map<String, Object> params = new HashMap<>();
                    params.put("startTime", eventTableDto.getStartTime());
                    params.put("endTime", eventTableDto.getEndTime());
                    // 执行SQL脚本抽取数据
                    List<Map<String, Object>> eventRows = DbHubMcpClient.extractRowsV2(dbHubMcpClient.callTool(
                            TOOLS_NAME, eventTableDto.getSqlScript(), params, syncDataDto.getHospitalSOID()));

                    if (eventRows != null && !eventRows.isEmpty()) {
                        // 插入数据到目标表
                        replaceTableData(targetTable, eventRows);
                        log.info("synced {} rows to table {} from eventTableList", eventRows.size(), targetTable);
                    } else {
                        log.info("no data returned from sqlScript for table {}", targetTable);
                    }
                } catch (Exception e) {
                    log.error("failed to execute sqlScript for table {}: {}", targetTable, e.getMessage());
                }
            }
        }

        List<Long> encounterIdList = new ArrayList<>();

        for (TableDataDto eventData : eventDataList) {

            String eventTable = eventData.getTable();
            String sqlScript = eventData.getSqlScript();

            if (StrUtil.isBlank(eventTable)) {
                continue;
            }

            log.info("start sync table {} data", eventTable);

            Map<String, Object> params = new HashMap<>();
            params.put("startTime", eventData.getStartTime());
            params.put("endTime", eventData.getEndTime());
            List<Map<String, Object>> rows = DbHubMcpClient.extractRowsV2(dbHubMcpClient.callTool(
                    TOOLS_NAME, eventData.getSqlScript(), params, syncDataDto.getHospitalSOID()));

            // Extract ENCOUNTER_ID from rows
            if (rows != null && !rows.isEmpty()) {
                for (Map<String, Object> row : rows) {
                    Object encounterId = row.get("ENCOUNTER_ID");
                    if (encounterId == null) {
                        encounterId = row.get("encounter_id");
                    }
                    if (encounterId != null) {
                        try {
                            encounterIdList.add(Long.valueOf(encounterId.toString()));
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse ENCOUNTER_ID: {}", encounterId);
                        }
                    }
                }
            }

            replaceTableData(eventTable, rows);
        }
        List<TableDataDto> bizDataList = syncDataDto.getBizDataList();
        if (CollUtil.isNotEmpty(bizDataList)) {
            for (TableDataDto bizData : bizDataList) {
                String bizTable = bizData.getTable();

                if (StrUtil.isBlank(bizTable)) {
                    continue;
                }
                if (!baseTableList.contains(bizTable) && !patableList.contains(bizTable)) {
                    log.info("table {} not in baseTableList or patableList, skip", bizTable);
                    continue;
                }

                Map<String, Object> bizParams = new HashMap<>();

                if (baseTableList.contains(bizTable)) {
                    log.info("start sync base table {} data with IS_DEL=0", bizTable);
                    bizParams.put("condition", "IS_DEL = 0");
                } else if (patableList.contains(bizTable)) {
                    log.info("start sync patient table {} data with encounterIdList", bizTable);
                    if (encounterIdList != null && !encounterIdList.isEmpty()) {
                        String encounterIds = encounterIdList.stream()
                                .distinct()
                                .map(String::valueOf)
                                .collect(Collectors.joining(","));
                        bizParams.put("encounterIds", encounterIds);
                    } else {
                        log.info("encounterIdList is empty, skip table {}", bizTable);
                        continue;
                    }
                }

                List<Map<String, Object>> bizRows = DbHubMcpClient.extractRowsV2(dbHubMcpClient.callTool(
                        TOOLS_NAME, bizData.getSqlScript(), bizParams, syncDataDto.getHospitalSOID()));

                replaceTableData(bizTable, bizRows);
            }
        }
        return "success";
    }

    private void replaceTableData(String tableName, List<Map<String, Object>> rows) {
        entityManager.createNativeQuery("DELETE FROM " + qualifyTable(tableName)).executeUpdate();
        log.info("cleared table {}", tableName);

        if (rows != null && !rows.isEmpty()) {
            String pkColumn = tableName.toUpperCase() + "_ID";
            List<Map<String, Object>> converted = rows.stream()
                    .map(row -> {
                        Map<String, Object> newRow = convertKeysToUpperSnakeCase(row);
                        newRow.put(pkColumn, snowflake.nextId());
                        return newRow;
                    })
                    .collect(Collectors.toList());
            insertRows(tableName, converted);
            log.info("inserted {} rows into table {}", converted.size(), tableName);
        }
    }

    private Map<String, Object> convertKeysToUpperSnakeCase(Map<String, Object> row) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            String newKey = StrUtil.isBlank(key) ? key : StrUtil.toUnderlineCase(key).toUpperCase();
            converted.put(newKey, entry.getValue());
        }
        return converted;
    }

    private void insertRows(String eventTable, List<Map<String, Object>> rows) {
        List<String> columns = resolveLocalColumns(eventTable);
        if (columns.isEmpty()) {
            log.warn("local table {} has no resolved columns, skip insert", eventTable);
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }

        // Always insert with full local table columns; missing/null values use DEFAULT.
        List<Map<String, Object>> normalizedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            Map<String, Object> upperRow = new HashMap<>(row.size());
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null) {
                    upperRow.put(entry.getKey().toUpperCase(), entry.getValue());
                }
            }
            Map<String, Object> fullRow = new LinkedHashMap<>(columns.size());
            for (String column : columns) {
                fullRow.put(column, upperRow.get(column));
            }
            normalizedRows.add(fullRow);
        }
        if (normalizedRows.isEmpty()) {
            log.warn("no valid rows to insert into table {}", eventTable);
            return;
        }

        batchInsert(qualifyTable(eventTable), columns, normalizedRows);
    }

    /**
     * Insert rows with multi-value SQL batches.
     * Null/missing values become DEFAULT so SQL Server column defaults still apply.
     * Batch size is limited by SQL Server parameter count (max 2100).
     */
    private void batchInsert(String tableName, List<String> columns, List<Map<String, Object>> rows) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            return;
        }

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
                        // Keep DB defaults for audit/not-null columns such as CREATED_AT.
                        placeholders.add("DEFAULT");
                    } else {
                        placeholders.add("?");
                        params.add(value);
                    }
                }
                valueGroups.add("(" + String.join(",", placeholders) + ")");
            }

            String sql = "INSERT INTO " + tableName + " (" + columnList + ") VALUES "
                    + String.join(",", valueGroups);

//            log.info("executable sql: {}", buildExecutableSql(sql, params));
            sqlServerJdbcTemplate.update(sql, params.toArray());
        }
        log.info("batch inserted {} rows into {} with {} columns", rows.size(), tableName, columns.size());
    }

    /**
     * Build a fully inlined SQL string for debugging/manual execution.
     * Values are escaped for SQL Server style literals.
     */
    private String buildExecutableSql(String sqlWithPlaceholders, List<Object> values) {
        StringBuilder executable = new StringBuilder();
        int valueIndex = 0;
        for (int i = 0; i < sqlWithPlaceholders.length(); i++) {
            char ch = sqlWithPlaceholders.charAt(i);
            if (ch == '?' && valueIndex < values.size()) {
                executable.append(formatSqlLiteral(values.get(valueIndex++)));
            } else {
                executable.append(ch);
            }
        }
        return executable.toString();
    }

    private String formatSqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof byte[] bytes) {
            return "0x" + bytesToHex(bytes);
        }
        String dateTimeText = formatDateTimeLiteral(value);
        if (dateTimeText != null) {
            return "N'" + dateTimeText + "'";
        }
        String text = String.valueOf(value).replace("'", "''");
        return "N'" + text + "'";
    }

    private String formatDateTimeLiteral(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return SQL_DATE_TIME_FORMATTER.format(localDateTime);
        }
        if (value instanceof LocalDate localDate) {
            return SQL_DATE_TIME_FORMATTER.format(localDate.atStartOfDay());
        }
        if (value instanceof LocalTime localTime) {
            return SQL_DATE_TIME_FORMATTER.format(localTime.atDate(LocalDate.of(1970, 1, 1)));
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return SQL_DATE_TIME_FORMATTER.format(offsetDateTime.toLocalDateTime());
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return SQL_DATE_TIME_FORMATTER.format(zonedDateTime.toLocalDateTime());
        }
        if (value instanceof Instant instant) {
            return SQL_DATE_TIME_FORMATTER.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
        }
        if (value instanceof java.util.Date date) {
            return SQL_DATE_TIME_FORMATTER.format(LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()));
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return SQL_DATE_TIME_FORMATTER.format(timestamp.toLocalDateTime());
        }
        if (value instanceof java.sql.Date date) {
            return SQL_DATE_TIME_FORMATTER.format(date.toLocalDate().atStartOfDay());
        }
        if (value instanceof java.sql.Time time) {
            return SQL_DATE_TIME_FORMATTER.format(time.toLocalTime().atDate(LocalDate.of(1970, 1, 1)));
        }
        if (value instanceof Calendar calendar) {
            return SQL_DATE_TIME_FORMATTER.format(LocalDateTime.ofInstant(calendar.toInstant(), calendar.getTimeZone().toZoneId()));
        }
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /**
     * Resolve SQL Server table column names via INFORMATION_SCHEMA.
     * Returns upper-case names for case-insensitive matching with remote fields.
     */
    private List<String> resolveLocalColumns(String tableName) {
        String cacheKey = tableName == null ? "" : tableName.toUpperCase();
        if (localColumnCache.containsKey(cacheKey)) {
            return localColumnCache.get(cacheKey);
        }

        List<String> columns = new ArrayList<>();
        try {
            String schema = resolveSchema();
            @SuppressWarnings("unchecked")
            List<Object> names = entityManager.createNativeQuery(
                            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                                    + "ORDER BY ORDINAL_POSITION")
                    .setParameter(1, schema)
                    .setParameter(2, tableName)
                    .getResultList();
            for (Object name : names) {
                if (name != null && !name.toString().isBlank()) {
                    columns.add(name.toString().toUpperCase());
                }
            }
        } catch (Exception e) {
            log.warn("failed to resolve columns for SQL Server table {}: {}", tableName, e.getMessage());
        }

        localColumnCache.put(cacheKey, columns);
        return columns;
    }

    private String qualifyTable(String tableName) {
        String schema = resolveSchema();
        if (schema == null || schema.isBlank()) {
            return tableName;
        }
        return schema + "." + tableName;
    }

    private String resolveSchema() {
        String schema = sqlServerProperties.getSchema();
        return (schema == null || schema.isBlank()) ? "dbo" : schema;
    }
}

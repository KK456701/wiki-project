package com.hospital.wikiagent.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.StrUtil;
import com.hospital.wikiagent.dbhub.DbHubMcpClient;
import com.hospital.wikiagent.dto.SyncDataDto;
import com.hospital.wikiagent.dto.TableDataDto;
import com.hospital.wikiagent.enums.BizTableEnum;
import com.hospital.wikiagent.sqlserver.SqlServerProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据同步服务
 * 负责从远程数据源（通过 DbHubMcpClient）抽取数据，并同步到本地 SQL Server 数据库。
 * 支持三类数据同步：
 * 1. 患者事件表（MRAS_PATIENT_EVENT）：通过 SQL 脚本抽取并关联事件号
 * 2. 事件数据表（eventDataList）：根据事件查询关联的就诊数据
 * 3. 业务数据表（bizDataList）：基础表全量同步 + 患者表按就诊ID批量同步
 */
@Service
@ConditionalOnProperty(prefix = "wiki.sqlserver", name = "enabled", havingValue = "true")
public class SyncDataService {

    private static final Logger log = LoggerFactory.getLogger(SyncDataService.class);
    private static final DateTimeFormatter SQL_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Keep under SQL Server 2100-parameter limit with some headroom. */
    private static final int SQL_SERVER_MAX_PARAMS = 2000;
    private static final int DEFAULT_INSERT_BATCH_ROWS = 200;

    private static final String TOOLS_NAME = "execCustomQuery";

    // 上一次会话ID
    private String lastConversationId = "";

    /**
     * 审计时间列：winex_aima 部分中间表（如 MRAS_BUSINESS_SUR_GRADE/
     * MRAS_BUSINESS_ANTI）这些列 NOT NULL 且无库端默认值，源行为空时
     * DEFAULT 占位会落成 NULL 导致整批 INSERT 失败，需补当前时间。
     */
    private static final Set<String> AUDIT_TIME_COLUMNS =
            Set.of("CREATED_AT", "MODIFIED_AT", "UPDATED_AT");

    private final Snowflake snowflake = new Snowflake(1, 1);

    private final DbHubMcpClient dbHubMcpClient;
    private final SqlServerProperties sqlServerProperties;
    private final JdbcTemplate sqlServerJdbcTemplate;

    @PersistenceContext(unitName = "sqlServer")
    private EntityManager entityManager;

    /** Local table column cache: key=table name upper, value=column info map (column name -> data type) */
    private final Map<String, Map<String, String>> localColumnCache = new HashMap<>();

    /** 基础配置表列表，这些表进行全量同步（IS_DEL=0） */
    private final List<String> baseTableList = new ArrayList<>() {{
        add("BUSINESS_UNIT_X_BU_TYPE");
        add("MRAS_MEDTECH_PRO");
        add("MRAS_MEDTECH_PROC");
        add("CLIBASIC_SURGERY");
    }};

    /**
     * 构造函数，注入所需的依赖
     *
     * @param dbHubMcpClient 数据库 MCP 客户端，用于调用远程数据源
     * @param sqlServerProperties SQL Server 配置属性
     * @param sqlServerJdbcTemplate SQL Server JDBC 模板，用于执行 SQL
     */
    public SyncDataService(DbHubMcpClient dbHubMcpClient,
                           SqlServerProperties sqlServerProperties,
                           @Qualifier("sqlServerJdbcTemplate") JdbcTemplate sqlServerJdbcTemplate) {
        this.dbHubMcpClient = dbHubMcpClient;
        this.sqlServerProperties = sqlServerProperties;
        this.sqlServerJdbcTemplate = sqlServerJdbcTemplate;
    }

    /**
     * 同步事件数据到本地数据库
     * 处理流程：
     * 1. 处理事件表数据（eventTableList）：执行SQL脚本抽取数据到MRAS_PATIENT_EVENT表
     * 2. 处理事件数据（eventDataList）：抽取数据并收集关联的就诊ID
     * 3. 处理业务数据（bizDataList）：
     *    - 基础表：全量同步（IS_DEL=0）
     *    - 患者表：按就诊ID批量同步（根据encounterIdList）
     *
     * @param syncDataDto 同步数据参数
     * @return "success" 表示同步成功
     */
    @Transactional("sqlServerTransactionManager")
    public String syncEventData(SyncDataDto syncDataDto) {
        List<TableDataDto> eventDataList = syncDataDto.getEventDataList();
//        if (eventDataList == null || eventDataList.isEmpty()) {
//            return "success";
//        }
        // 防止模型解析异常导致的死循环
        if (Objects.nonNull(syncDataDto.getCaliber()) && (syncDataDto.getCaliber() > 10 || syncDataDto.getCaliber() < 1)) {
            return "failure";
        }

        Integer caliber = syncDataDto.getCaliber();
        String conversationId = syncDataDto.getConversationId();
        boolean sameConversation = StrUtil.isNotBlank(conversationId) && conversationId.equals(lastConversationId);

        // 是否需要清除数据：无口径 或 非同一会话
        boolean needClearData = caliber == null || !sameConversation;
        // 不需要同步基础表数据：同一会话 且 第 N 个口径（N > 1），第一个口径已经同步过基础表
        boolean notSyncBaseTable = sameConversation && caliber != null && caliber > 1;

        //MRAS_PATIENT_EVENT患者事件表数据同步
        List<TableDataDto> eventTableList = syncDataDto.getEventTableList();

        // 当eventTableList非空时，执行sqlScript抽取数据到SqlServer
        if (CollUtil.isNotEmpty(eventTableList)) {
            for (TableDataDto eventTableDto : eventTableList) {
                String eventNo = eventTableDto.getEventNo();
                String targetTable = eventTableDto.getTable();
                String sqlScript = eventTableDto.getSqlScript();

                if (StrUtil.isBlank(targetTable) || StrUtil.isBlank(sqlScript) || !"MRAS_PATIENT_EVENT".equals(targetTable)) {
                    log.warn("eventTableList 表名或 sqlScript 为空，跳过");
                    continue;
                }

                log.info("开始为表 {} 执行 sqlScript", targetTable);
                try {
                    Map<String, Object> params = new HashMap<>();
                    params.put("startTime", eventTableDto.getStartTime());
                    params.put("endTime", eventTableDto.getEndTime());
                    // 执行SQL脚本抽取数据
                    List<Map<String, Object>> eventRows = DbHubMcpClient.extractRowsV2(dbHubMcpClient.callTool(
                            TOOLS_NAME, eventTableDto.getSqlScript(), params, syncDataDto.getHospitalSOID()));

                    if (eventRows != null && !eventRows.isEmpty()) {
                        // 为每行添加 EVENT_NO 字段
                        for (Map<String, Object> row : eventRows) {
                            row.put("EVENT_NO", eventNo);
                        }
                        // 插入数据到目标表
                        replaceTableData(syncDataDto, targetTable, eventRows, StrUtil.isBlank(eventNo) ? "" : "EVENT_NO = '" + eventNo + "'", needClearData, "BIZ_ID");
                        log.info("已从 eventTableList 同步 {} 行数据到表 {}", eventRows.size(), targetTable);
                    } else {
                        log.info("表 {} 的 sqlScript 未返回数据", targetTable);
                    }
                } catch (Exception e) {
                    log.error("为表 {} 执行 sqlScript 失败: {}", targetTable, e.getMessage());
                }
            }
        }

        Set<Long> encounterIdList = new HashSet<>();

        for (TableDataDto eventData : eventDataList) {

            String eventTable = eventData.getTable();

            if (StrUtil.isBlank(eventTable)) {
                continue;
            }

            // 先判断是否是多口径的数据同步

            log.info("开始同步表 {} 数据", eventTable);

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
                    if (encounterId == null) {
                        encounterId = row.get("encounterId");
                    }
                    if (encounterId != null) {
                        try {
                            encounterIdList.add(Long.valueOf(encounterId.toString()));
                        } catch (NumberFormatException e) {
                            log.warn("解析 ENCOUNTER_ID 失败: {}", encounterId);
                        }
                    }
                }
            }
            if (Objects.nonNull(syncDataDto.getCaliber())) {
                eventTable = copyTableMultipleTimes(eventTable, syncDataDto.getCaliber());
            }
            replaceTableData(syncDataDto, eventTable, rows, "", true, "");
        }
        List<TableDataDto> bizDataList = syncDataDto.getBizDataList();
        if (CollUtil.isNotEmpty(bizDataList)) {
            for (TableDataDto bizData : bizDataList) {
                String bizTable = bizData.getTable();
                String sqlScript = bizData.getSqlScript();
                if (StrUtil.isBlank(bizTable)) {
                    continue;
                }
                if (!baseTableList.contains(bizTable) && !BizTableEnum.getTableList().contains(bizTable)) {
                    log.info("表 {} 不在基础表列表或患者表列表中，跳过", bizTable);
                    continue;
                }

                Map<String, Object> bizParams = new HashMap<>();
                if (baseTableList.contains(bizTable)) {
                    if (notSyncBaseTable) {
                        continue;
                    }
                    log.info("开始全量同步基础表 {}（IS_DEL=0）", bizTable);
                    if (StrUtil.isBlank(sqlScript)) {
                        sqlScript = "select * from " + bizTable + " where IS_DEL = 0";
                    }
                    List<Map<String, Object>> bizRows = DbHubMcpClient.extractRowsV2(dbHubMcpClient.callTool(
                            TOOLS_NAME, sqlScript, bizParams, syncDataDto.getHospitalSOID()));
                    replaceTableData(syncDataDto, bizTable, bizRows, "", needClearData, "");
                } else if (BizTableEnum.getTableList().contains(bizTable)) {
                    log.info("开始按就诊ID同步患者表 {} 数据", bizTable);
                    if (CollUtil.isEmpty(encounterIdList)) {
                        log.info("就诊ID列表为空，跳过表 {}", bizTable);
                        continue;
                    }
                    if (StrUtil.isBlank(sqlScript)) {
                        String condition = BizTableEnum.getConditionByTable(bizTable);
                        if (StrUtil.isBlank(condition)) {
                            condition = " ENCOUNTER_ID in (:encounterIds)";
                        }
                        sqlScript = "select * from " + bizTable + " where IS_DEL = 0 and " + condition;
                    }
                    List<String> distinctIds = encounterIdList.stream()
                            .map(String::valueOf)
                            .collect(Collectors.toList());

                    int batchSize = 1800;
                    List<Map<String, Object>> allBizRows = new ArrayList<>();

                    for (int i = 0; i < distinctIds.size(); i += batchSize) {
                        List<String> batchIds = distinctIds.subList(i, Math.min(i + batchSize, distinctIds.size()));
                        String encounterIds = String.join(",", batchIds);
                        String batchSql = sqlScript.replace(":encounterIds", encounterIds);
                        log.info("正在同步患者表 {} 第 {} / {} 批，就诊ID数量: {}",
                                bizTable, i / batchSize + 1,
                                (distinctIds.size() + batchSize - 1) / batchSize,
                                batchIds.size());
                        List<Map<String, Object>> bizRows = DbHubMcpClient.extractRowsV2(dbHubMcpClient.callTool(
                                TOOLS_NAME, batchSql, bizParams, syncDataDto.getHospitalSOID()));
                        if (bizRows != null) {
                            allBizRows.addAll(bizRows);
                        }
                    }
                    String bizId = bizTable.replaceFirst("_\\d+$", "") + "_ID";
                    replaceTableData(syncDataDto, bizTable, allBizRows, "", needClearData, bizId);
                }
            }
        }
        lastConversationId = conversationId;
        return "success";
    }

    /**
     * 替换表数据：先清空再插入
     *
     * @param tableName 目标表名
     * @param rows 要插入的数据行
     * @param condition 清空表时的WHERE条件（为空则清空全表）
     */
    private void replaceTableData(SyncDataDto syncDataDto, String tableName, List<Map<String, Object>> rows, String condition, Boolean needClearData, String bizId) {
        if (needClearData) {
            String clearedTableSql = "DELETE FROM " + qualifyTable(tableName) + (StrUtil.isBlank(condition) ? "" : " WHERE " + condition);
            entityManager.createNativeQuery(clearedTableSql).executeUpdate();
            log.info("已清空表 {}", tableName);
        }

        if (rows != null && !rows.isEmpty()) {
            String pkColumn = tableName.toUpperCase().replaceFirst("_\\d+$", "") + "_ID";
            List<Map<String, Object>> converted = rows.stream()
                    .map(row -> {
                        Map<String, Object> newRow = convertKeysToUpperSnakeCase(row);
                        Object existingPk = newRow.get(pkColumn);
                        if (existingPk == null || (existingPk instanceof CharSequence cs && cs.toString().isBlank())) {
                            newRow.put(pkColumn, snowflake.nextId());
                        }
                        return newRow;
                    })
                    .collect(Collectors.toList());
            insertRows(tableName, converted, needClearData, bizId);
            log.info("已插入 {} 行数据到表 {}", converted.size(), tableName);
        }
    }

    /**
     * 将Map的key转换为大写下划线格式（UPPER_SNAKE_CASE）
     *
     * @param row 原始数据行
     * @return 转换后的数据行
     */
    private Map<String, Object> convertKeysToUpperSnakeCase(Map<String, Object> row) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            String newKey = StrUtil.isBlank(key) ? key : StrUtil.toUnderlineCase(key).toUpperCase();
            converted.put(newKey, entry.getValue());
        }
        return converted;
    }

    /**
     * 插入数据行到指定表
     * 根据本地表结构补齐字段，空值使用数据库默认值
     *
     * @param eventTable 目标表名
     * @param rows 要插入的数据行
     */
    private void insertRows(String eventTable, List<Map<String, Object>> rows, Boolean needClearData, String bizId) {
        Map<String, String> columnTypes = resolveLocalColumns(eventTable);
        if (columnTypes.isEmpty()) {
            log.warn("本地表 {} 无可用列信息，跳过插入", eventTable);
            return;
        }
        List<String> columns = new ArrayList<>(columnTypes.keySet());
        if (rows == null || rows.isEmpty()) {
            return;
        }

        // 拼接所有字段，空值用 DEFAULT
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
            log.warn("没有有效数据行可插入表 {}", eventTable);
            return;
        }

        batchInsert(qualifyTable(eventTable), columns, columnTypes, normalizedRows, needClearData, bizId);
    }

    /**
     * Insert rows with multi-value SQL batches.
     * Null/missing values become DEFAULT so SQL Server column defaults still apply.
     * Batch size is limited by SQL Server parameter count (max 2100).
     */
    /**
     * 批量插入数据行
     * 使用多值 SQL 批量插入，空值使用 DEFAULT 以保留数据库默认值
     * 批量大小受 SQL Server 参数数量限制（最大 2100）
     *
     * @param tableName 目标表名（含 schema）
     * @param columns 列名列表
     * @param columnTypes 列名到数据类型的映射
     * @param rows 要插入的数据行
     */
    private void batchInsert(String tableName, List<String> columns, Map<String, String> columnTypes, List<Map<String, Object>> rows, Boolean needClearData, String bizId) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            return;
        }

        // 非清空模式下，按 bizId 排重：查询现有表中已存在的 bizId，跳过重复行
        List<Map<String, Object>> insertRows = rows;
        if (!needClearData && StrUtil.isNotBlank(bizId) && columns.contains(bizId)) {
            Set<String> bizIds = rows.stream()
                    .map(row -> row.get(bizId))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toSet());
            if (!bizIds.isEmpty()) {
                Set<String> existing = loadExistingBizIds(tableName, bizId, bizIds);
                insertRows = rows.stream()
                        .filter(row -> {
                            Object val = row.get(bizId);
                            return val == null || !existing.contains(val.toString());
                        })
                        .collect(Collectors.toList());
                log.info("表 {} 按 {} 排重：待插入 {} 行，已存在 {} 行，实际插入 {} 行",
                        tableName, bizId, rows.size(), rows.size() - insertRows.size(), insertRows.size());
            }
        }
        if (insertRows.isEmpty()) {
            return;
        }

        int maxRowsPerBatch = Math.max(1, Math.min(
                DEFAULT_INSERT_BATCH_ROWS,
                SQL_SERVER_MAX_PARAMS / columns.size()));
        String columnList = String.join(",", columns);

        for (int from = 0; from < insertRows.size(); from += maxRowsPerBatch) {
            int to = Math.min(from + maxRowsPerBatch, insertRows.size());
            List<Map<String, Object>> batch = insertRows.subList(from, to);

            List<Object> params = new ArrayList<>(batch.size() * columns.size());
            List<String> valueGroups = new ArrayList<>(batch.size());
            for (Map<String, Object> row : batch) {
                List<String> placeholders = new ArrayList<>(columns.size());
                for (String column : columns) {
                    Object value = row.get(column);
                    if (value == null) {
                        if (isAuditTimestampColumn(column, columnTypes.get(column))) {
                            // 审计时间列无库端默认值时 DEFAULT 会落成 NULL，补当前时间
                            placeholders.add("?");
                            params.add(new java.sql.Timestamp(System.currentTimeMillis()));
                        } else {
                            // Keep DB defaults for other nullable/defaulted columns.
                            placeholders.add("DEFAULT");
                        }
                    } else {
                        // 根据列的数据类型转换值
                        String dataType = columnTypes.get(column);
                        Object convertedValue = convertValueByType(value, dataType);
                        placeholders.add("?");
                        params.add(convertedValue);
                    }
                }
                valueGroups.add("(" + String.join(",", placeholders) + ")");
            }

            String sql = "INSERT INTO " + tableName + " (" + columnList + ") VALUES "
                    + String.join(",", valueGroups);

//            log.info("executable sql: {}", buildExecutableSql(sql, params));
            sqlServerJdbcTemplate.update(sql, params.toArray());
        }
        log.info("已批量插入 {} 行到表 {}（{} 列）", insertRows.size(), tableName, columns.size());
    }

    /**
     * 查询表中已存在的指定 bizId 值集合。
     * 分批查询避免 IN 子句过大。
     */
    private Set<String> loadExistingBizIds(String tableName, String bizIdColumn, Set<String> bizIds) {
        Set<String> result = new HashSet<>();
        List<String> values = new ArrayList<>(bizIds);
        int batchSize = 500;
        for (int i = 0; i < values.size(); i += batchSize) {
            List<String> batch = values.subList(i, Math.min(i + batchSize, values.size()));
            String placeholders = batch.stream().map(v -> "?").collect(Collectors.joining(","));
            String sql = "SELECT " + bizIdColumn + " FROM " + tableName
                    + " WHERE " + bizIdColumn + " IN (" + placeholders + ")";
            List<Object> queryRows = sqlServerJdbcTemplate.queryForList(sql, Object.class, batch.toArray());
            for (Object row : queryRows) {
                if (row != null) {
                    result.add(row.toString());
                }
            }
        }
        return result;
    }

    /**
     * 判断是否为需补当前时间的审计时间列（列名在名单内且为日期类型）。
     */
    private static boolean isAuditTimestampColumn(String column, String dataType) {
        return column != null && dataType != null
                && dataType.contains("date")
                && AUDIT_TIME_COLUMNS.contains(column);
    }

    /**
     * 根据数据库列的数据类型转换值，避免类型不匹配导致的 SQL 错误
     */
    private Object convertValueByType(Object value, String dataType) {
        if (value == null || dataType == null) {
            return value;
        }
        // 如果已经是数值类型，直接返回
        if (value instanceof Number) {
            return value;
        }
        // 对于数值类型列，如果值是字符串，尝试转换为数值
        if (value instanceof String strValue) {
            if (strValue.isBlank()) {
                return null;
            }
            // SQL Server 数值类型
            if (isNumericType(dataType)) {
                try {
                    // 优先尝试 Long
                    if (!strValue.contains(".")) {
                        return Long.parseLong(strValue);
                    }
                    // 有小数点，使用 BigDecimal
                    return new java.math.BigDecimal(strValue);
                } catch (NumberFormatException e) {
                    log.warn("无法将 '{}' 转换为数值类型 {}，保留原值", strValue, dataType);
                    return value;
                }
            }
        }
        return value;
    }

    /**
     * 判断 SQL Server 数据类型是否为数值类型
     */
    private boolean isNumericType(String dataType) {
        if (dataType == null) {
            return false;
        }
        String lower = dataType.toLowerCase();
        return lower.contains("int") || lower.contains("numeric")
                || lower.contains("decimal") || lower.contains("float")
                || lower.contains("real") || lower.contains("money");
    }

    /**
     * Build a fully inlined SQL string for debugging/manual execution.
     * Values are escaped for SQL Server style literals.
     */
    /**
     * 构建完全内联的 SQL 字符串（用于调试/手动执行）
     * 值按照 SQL Server 风格的字面量进行转义
     *
     * @param sqlWithPlaceholders 带占位符的 SQL
     * @param values 参数值列表
     * @return 完全内联的 SQL 字符串
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

    /**
     * 格式化 SQL 字面量值
     * 支持数字、布尔、字节数组、日期时间、字符串等类型
     *
     * @param value 要格式化的值
     * @return SQL 字面量字符串
     */
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

    /**
     * 将各种日期时间类型格式化为统一的日期时间字符串
     * 支持的类型：LocalDateTime, LocalDate, LocalTime, OffsetDateTime,
     * ZonedDateTime, Instant, java.util.Date, java.sql.Timestamp,
     * java.sql.Date, java.sql.Time, Calendar
     *
     * @param value 日期时间对象
     * @return 格式化后的字符串，不支持的类型返回 null
     */
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

    /**
     * 将字节数组转换为十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串（大写）
     */
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
    /**
     * 解析本地 SQL Server 表的列信息
     * 通过 INFORMATION_SCHEMA.COLUMNS 查询，返回列名（大写）到数据类型（小写）的映射
     * 结果会被缓存到 localColumnCache 中
     *
     * @param tableName 表名
     * @return 列名到数据类型的映射
     */
    private Map<String, String> resolveLocalColumns(String tableName) {
        String cacheKey = tableName == null ? "" : tableName.toUpperCase();
        if (localColumnCache.containsKey(cacheKey)) {
            return localColumnCache.get(cacheKey);
        }

        Map<String, String> columns = new LinkedHashMap<>();
        try {
            String schema = resolveSchema();
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createNativeQuery(
                            "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                                    + "ORDER BY ORDINAL_POSITION")
                    .setParameter(1, schema)
                    .setParameter(2, tableName)
                    .getResultList();
            for (Object[] row : rows) {
                if (row != null && row.length >= 2 && row[0] != null && !row[0].toString().isBlank()) {
                    columns.put(row[0].toString().toUpperCase(), row[1].toString().toLowerCase());
                }
            }
        } catch (Exception e) {
            log.warn("解析 SQL Server 表 {} 列信息失败: {}", tableName, e.getMessage());
        }

        localColumnCache.put(cacheKey, columns);
        return columns;
    }

    /**
     * 为表名添加 schema 前缀
     *
     * @param tableName 表名
     * @return 带 schema 前缀的表名
     */
    private String qualifyTable(String tableName) {
        String schema = resolveSchema();
        if (schema == null || schema.isBlank()) {
            return tableName;
        }
        return schema + "." + tableName;
    }

    /**
     * 获取配置的 schema 名称，如果未配置则返回默认值 "dbo"
     *
     * @return schema 名称
     */
    private String resolveSchema() {
        String schema = sqlServerProperties.getSchema();
        return (schema == null || schema.isBlank()) ? "dbo" : schema;
    }

    /**
     * 复制表结构并创建多个副本表
     * 命名规则：原表名_1, 原表名_2, 原表名_3...
     * 复制前先检查目标表是否存在，存在则先删除
     *
     * @param sourceTableName 原表名
     * @param caliberNo 第几个口径
     * @return "success" 表示成功
     */
    public String copyTableMultipleTimes(String sourceTableName, int caliberNo) {
        if (StrUtil.isBlank(sourceTableName)) {
            log.warn("源表名为空");
            throw new RuntimeException("源表名为空");
        }

        String schema = resolveSchema();
        String qualifiedSourceTable = qualifyTable(sourceTableName);

        // 检查源表是否存在
        String checkSourceSql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        Integer sourceCount = sqlServerJdbcTemplate.queryForObject(
                checkSourceSql, Integer.class, schema, sourceTableName);
        if (sourceCount == null || sourceCount == 0) {
            log.warn("源表 {} 不存在", qualifiedSourceTable);
            throw new RuntimeException("源表不存在");
        }

        String targetTableName = sourceTableName + "_" + caliberNo;
        String qualifiedTargetTable = qualifyTable(targetTableName);

        // 先判断再删除
        String dropSql = "DROP TABLE IF EXISTS " + qualifiedTargetTable;
        sqlServerJdbcTemplate.execute(dropSql);
        log.info("已删除已存在的表 {}", qualifiedTargetTable);

        // 复制表结构和数据：SELECT * INTO new_table FROM source_table
        String copySql = "SELECT * INTO " + qualifiedTargetTable + " FROM " + qualifiedSourceTable + " where 1 = 0";
        sqlServerJdbcTemplate.execute(copySql);
        log.info("已复制表 {} 到 {}", qualifiedSourceTable, qualifiedTargetTable);

        log.info("成功创建表 {}", targetTableName);
        return targetTableName;
    }
}

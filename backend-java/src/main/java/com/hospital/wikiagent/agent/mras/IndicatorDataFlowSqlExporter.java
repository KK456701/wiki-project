package com.hospital.wikiagent.agent.mras;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.dbhub.DbHubProperties;
import com.hospital.wikiagent.dbhub.DbHubProperties.Source;

/**
 * 把数据链路节点中的知识库模板 SQL 转成可直接粘贴到 Navicat SQL Server 执行的脚本。
 * 原模板保存在 {@code templateSql}，页面主展示字段 {@code sql} 则替换为带 USE、变量声明、
 * 已裁剪 #ETC/#EQUALS 且修正 NOLOCK 方言的脚本。该组件只转换文本，不访问数据库。
 */
@Component
public class IndicatorDataFlowSqlExporter {

    private static final Pattern NAMED_PARAMETER =
            Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern DECLARED_PARAMETER =
            Pattern.compile("(?im)^DECLARE\\s+@([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final DateTimeFormatter SQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MrasTemplateRenderer templateRenderer;
    private final DbHubProperties dbHubProperties;

    public IndicatorDataFlowSqlExporter(
            MrasTemplateRenderer templateRenderer,
            DbHubProperties dbHubProperties) {
        this.templateRenderer = templateRenderer;
        this.dbHubProperties = dbHubProperties;
    }

    public Map<String, Object> enrichRule(
            Map<String, Object> rule, String statStart, String statEnd) {
        if (rule == null || rule.isEmpty()) return rule;
        Object rawFlow = rule.get("dataFlow");
        if (!(rawFlow instanceof Map<?, ?> flow)) return rule;

        LocalDateTime start = parseTime(statStart, defaultStart());
        LocalDateTime end = parseTime(statEnd, defaultEnd());
        Map<String, Object> enrichedFlow = enrichFlow(cast(flow), start, end);
        Map<String, Object> result = new LinkedHashMap<>(rule);
        result.put("dataFlow", enrichedFlow);
        return readOnlyMapAllowingNull(result);
    }

    Map<String, Object> enrichFlow(
            Map<String, Object> flow, LocalDateTime start, LocalDateTime end) {
        Object rawNodes = flow.get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) return flow;
        List<Map<String, Object>> enrichedNodes = new ArrayList<>();
        for (Object rawNode : nodes) {
            if (!(rawNode instanceof Map<?, ?> node)) continue;
            enrichedNodes.add(enrichNode(cast(node), start, end));
        }
        Map<String, Object> result = new LinkedHashMap<>(flow);
        result.put("nodes", List.copyOf(enrichedNodes));
        result.put("sqlDisplayFormat", "SQLSERVER_NAVICAT");
        result.put("sqlStatStart", start.format(SQL_TIME));
        result.put("sqlStatEnd", end.format(SQL_TIME));
        return readOnlyMapAllowingNull(result);
    }

    private Map<String, Object> enrichNode(
            Map<String, Object> node, LocalDateTime start, LocalDateTime end) {
        String templateSql = text(node.get("sql"));
        if (templateSql.isBlank()) return node;
        String role = text(node.get("databaseRole"));
        Source source = "BUSINESS".equals(role) || "SYNC".equals(role)
                ? dbHubProperties.businessSource() : dbHubProperties.realSource();
        String executable = executableSql(
                templateSql, strings(node.get("tableNames")), source, role, start, end);

        Map<String, Object> result = new LinkedHashMap<>(node);
        result.put("templateSql", templateSql);
        result.put("sql", executable);
        result.put("sqlExecutable", true);
        result.put("sqlDialect", "sqlserver");
        result.put("sqlClient", "Navicat");
        result.put("parameters", declaredParameters(executable));
        result.put("databaseName", source.getDatabaseName());
        result.put("schemaName", source.getSchemaName());
        return readOnlyMapAllowingNull(result);
    }

    private String executableSql(
            String templateSql,
            List<String> tableNames,
            Source source,
            String role,
            LocalDateTime start,
            LocalDateTime end) {
        Map<String, Object> templateParams = new LinkedHashMap<>();
        templateParams.put("marptBeginAt", start.format(SQL_TIME));
        templateParams.put("marptEndAt", end.format(SQL_TIME));
        templateParams.put("startTime", start.format(SQL_TIME));
        templateParams.put("endTime", end.format(SQL_TIME));
        templateParams.put("syncType", "outHosp");

        String rendered = templateRenderer.renderTemplate(templateSql, templateParams);
        rendered = MrasSqlExecutionService.stripLeadingTrailingQuotes(rendered);
        Set<String> parameters = parameters(rendered);
        String body = replaceParameters(rendered);
        body = qualifyPhysicalTables(body, tableNames, source.getSchemaName());

        StringBuilder sql = new StringBuilder();
        sql.append("-- 数据链路可执行脚本（SQL Server / Navicat）\n")
                .append("-- 数据库侧：").append(databaseRoleLabel(role)).append("；数据库：")
                .append(source.getDatabaseName()).append("；默认架构：")
                .append(source.getSchemaName()).append("\n")
                .append("-- 时间范围来自当前指标卡片；需要试算其他周期时只修改下方变量。\n");
        if ("SYNC".equals(role)) {
            sql.append("-- 此脚本复现源数据查询，不执行系统清表、复制或写入中间表。\n");
        }
        sql.append("USE ").append(bracket(source.getDatabaseName())).append(";\n")
                .append("SET NOCOUNT ON;\n\n");
        for (String parameter : parameters) {
            sql.append(declaration(parameter, start, end)).append("\n");
        }
        if (!parameters.isEmpty()) sql.append("\n");
        sql.append(body.strip()).append("\n");
        return sql.toString();
    }

    /**
     * Navicat 中切换数据库并不保证登录账号的默认 schema 正确。只对血缘分析已经确认的
     * 物理表补 schema，CTE、派生表和临时别名不参与替换。
     */
    private static String qualifyPhysicalTables(
            String sql, List<String> tableNames, String schemaName) {
        if (sql == null || sql.isBlank() || tableNames.isEmpty()
                || schemaName == null || schemaName.isBlank()) return sql;
        String result = sql;
        for (String table : tableNames) {
            if (table == null || table.isBlank()) continue;
            String bare = table.replace("[", "").replace("]", "").strip();
            if (bare.contains(".")) continue;
            Pattern reference = Pattern.compile(
                    "(?i)\\b(FROM|JOIN)\\s+(\\[?" + Pattern.quote(bare) + "\\]?)\\b");
            Matcher matcher = reference.matcher(result);
            String replacement = "$1 " + bracket(schemaName) + "." + bracket(bare);
            result = matcher.replaceAll(replacement);
        }
        return result;
    }

    private static String declaration(
            String parameter, LocalDateTime start, LocalDateTime end) {
        return switch (parameter) {
            case "marptBeginAt", "startTime" -> "DECLARE @" + parameter
                    + " DATETIME2(0) = '" + start.format(SQL_TIME) + "';";
            case "marptEndAt", "endTime" -> "DECLARE @" + parameter
                    + " DATETIME2(0) = '" + end.format(SQL_TIME) + "';";
            case "syncType" -> "DECLARE @syncType NVARCHAR(20) = N'outHosp';";
            default -> "DECLARE @" + parameter
                    + " NVARCHAR(MAX) = NULL; -- 可选筛选参数，按需填写";
        };
    }

    private static Set<String> parameters(String sql) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = NAMED_PARAMETER.matcher(sql == null ? "" : sql);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static List<String> declaredParameters(String sql) {
        List<String> result = new ArrayList<>();
        Matcher matcher = DECLARED_PARAMETER.matcher(sql == null ? "" : sql);
        while (matcher.find()) result.add(matcher.group(1));
        return List.copyOf(result);
    }

    private static String replaceParameters(String sql) {
        Matcher matcher = NAMED_PARAMETER.matcher(sql == null ? "" : sql);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement("@" + matcher.group(1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static LocalDateTime parseTime(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.strip().replace('T', ' ');
        try {
            return LocalDateTime.parse(normalized, SQL_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(normalized.substring(0, 10)).atStartOfDay();
            } catch (RuntimeException ignoredAgain) {
                return fallback;
            }
        }
    }

    private static LocalDateTime defaultStart() {
        return LocalDate.of(LocalDate.now().getYear() - 1, 1, 1).atStartOfDay();
    }

    private static LocalDateTime defaultEnd() {
        return LocalDate.of(LocalDate.now().getYear(), 1, 1).atStartOfDay();
    }

    private static String databaseRoleLabel(String role) {
        return "BUSINESS".equals(role) || "SYNC".equals(role) ? "业务库" : "真实库";
    }

    private static String bracket(String identifier) {
        return "[" + (identifier == null ? "" : identifier.replace("]", "]]")) + "]";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /**
     * Effective rule data may contain legitimate null values for optional fields.
     * Map.copyOf rejects those values, which would turn a read-only metadata
     * endpoint into HTTP 500. Keep the map unmodifiable while preserving nulls.
     */
    private static <K, V> Map<K, V> readOnlyMapAllowingNull(Map<K, V> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(IndicatorDataFlowSqlExporter::text)
                .filter(item -> !item.isBlank()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}

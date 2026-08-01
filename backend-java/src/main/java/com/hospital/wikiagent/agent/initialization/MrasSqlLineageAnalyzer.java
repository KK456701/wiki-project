package com.hospital.wikiagent.agent.initialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 对知识库 T-SQL 做受限词法分析，只提取初始化校验需要的物理表、限定字段和简单等值关联。
 * 分析器会区分物理表、CTE、派生查询和别名，并将不能安全证明的结构显式记录为警告。
 * 它不改写计算 SQL，也不补充业务语义；无法证明的依赖由上层失败关闭或显示为未完成检查。
 */
@Component
public class MrasSqlLineageAnalyzer {

    private static final Pattern TOKEN = Pattern.compile(
            "\\[([^]]+)]|[A-Za-z_][A-Za-z0-9_$#]*|\\.|,|\\(|\\)|=");
    private static final Set<String> RESERVED = Set.of(
            "SELECT", "FROM", "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER",
            "CROSS", "ON", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT",
            "INTERSECT", "AS", "WITH", "NOLOCK", "AND", "OR", "WHEN", "THEN", "ELSE",
            "END", "CASE", "BY", "OFFSET", "FETCH", "FOR", "OPTION");

    public SqlLineage analyze(String sql) {
        String source = stripSqlEnvelope(sql == null ? "" : sql);
        String normalized = maskCommentsAndLiterals(source);
        List<Token> tokens = tokenize(normalized);
        Set<String> ctes = collectCtes(tokens);
        AliasCatalog aliasCatalog = collectTablesAndAliases(tokens, ctes);
        Map<String, String> aliases = aliasCatalog.aliases();

        Map<String, Set<String>> fields = new LinkedHashMap<>();
        for (int index = 0; index + 2 < tokens.size(); index++) {
            Token left = tokens.get(index);
            Token dot = tokens.get(index + 1);
            Token right = tokens.get(index + 2);
            if (!left.identifier() || !".".equals(dot.text()) || !right.identifier()) {
                continue;
            }
            String alias = upper(left.text());
            if (aliasCatalog.ambiguousAliases().contains(alias)) continue;
            String table = aliases.get(alias);
            if (table != null && !"*".equals(right.text())) {
                fields.computeIfAbsent(table, ignored -> new LinkedHashSet<>())
                        .add(upper(right.text()));
            }
        }

        List<JoinEdge> joins = collectJoins(
                tokens, aliases, aliasCatalog.ambiguousAliases(), ctes);
        Set<String> tables = aliasCatalog.tables();
        List<String> warnings = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            warnings.add("SQL 为空");
        } else if (tables.isEmpty()) {
            warnings.add("未能识别物理表");
        }
        if (normalized.contains("#EQUALS") || normalized.contains("#ETC")
                || normalized.contains("#{")) {
            warnings.add("SQL 仍包含未渲染模板标记");
        }
        if (!aliasCatalog.ambiguousAliases().isEmpty()) {
            warnings.add("派生查询或不同查询层使用的别名无法安全归属物理表，相关字段与关联未完成检查："
                    + String.join(",", aliasCatalog.ambiguousAliases()));
        }
        return new SqlLineage(
                List.copyOf(tables), immutableFields(fields), List.copyOf(joins),
                List.copyOf(ctes), List.copyOf(warnings));
    }

    private static AliasCatalog collectTablesAndAliases(
            List<Token> tokens, Set<String> ctes) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> tables = new LinkedHashSet<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        // 派生表、CTE 与表值表达式的列来自查询结果，不能归到内层物理表。
        for (int index = 0; index < tokens.size(); index++) {
            String keyword = upper(tokens.get(index).text());
            if (!"FROM".equals(keyword) && !"JOIN".equals(keyword)) {
                continue;
            }
            String nonPhysicalAlias = nonPhysicalAlias(tokens, index + 1, ctes);
            if (nonPhysicalAlias != null) {
                ambiguous.add(nonPhysicalAlias);
            }
        }
        for (int index = 0; index < tokens.size(); index++) {
            String keyword = upper(tokens.get(index).text());
            if (!"FROM".equals(keyword) && !"JOIN".equals(keyword)) {
                continue;
            }
            TableRef ref = tableRef(tokens, index + 1, ctes);
            if (ref == null) {
                continue;
            }
            tables.add(ref.table());
            aliases.putIfAbsent(ref.table(), ref.table());
            if (ref.alias() != null) {
                String previous = aliases.get(ref.alias());
                if (previous != null && !previous.equals(ref.table())) {
                    aliases.remove(ref.alias());
                    ambiguous.add(ref.alias());
                } else if (!ambiguous.contains(ref.alias())) {
                    aliases.put(ref.alias(), ref.table());
                }
            }
        }
        return new AliasCatalog(
                Map.copyOf(aliases), Set.copyOf(tables), Set.copyOf(ambiguous));
    }

    private static String nonPhysicalAlias(
            List<Token> tokens, int start, Set<String> ctes) {
        if (start >= tokens.size()) return null;
        if ("(".equals(tokens.get(start).text())) {
            int close = matchingClose(tokens, start);
            return aliasAfter(tokens, close + 1);
        }
        if (!tokens.get(start).identifier()) return null;
        String name = upper(tokens.get(start).text());
        if (ctes.contains(name)) {
            String alias = aliasAfter(tokens, start + 1);
            return alias == null ? name : alias;
        }
        int cursor = start + 1;
        while (cursor + 1 < tokens.size() && ".".equals(tokens.get(cursor).text())
                && tokens.get(cursor + 1).identifier()) {
            cursor += 2;
        }
        if (cursor < tokens.size() && "(".equals(tokens.get(cursor).text())) {
            int close = matchingClose(tokens, cursor);
            return aliasAfter(tokens, close + 1);
        }
        return null;
    }

    private static String aliasAfter(List<Token> tokens, int start) {
        int index = start;
        if (index < tokens.size() && "AS".equals(upper(tokens.get(index).text()))) {
            index++;
        }
        if (index < tokens.size() && tokens.get(index).identifier()
                && !RESERVED.contains(upper(tokens.get(index).text()))) {
            return upper(tokens.get(index).text());
        }
        return null;
    }

    private static List<JoinEdge> collectJoins(
            List<Token> tokens,
            Map<String, String> aliases,
            Set<String> ambiguousAliases,
            Set<String> ctes) {
        List<JoinEdge> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!"JOIN".equals(upper(tokens.get(index).text()))) {
                continue;
            }
            TableRef rightRef = tableRef(tokens, index + 1, ctes);
            if (rightRef == null) {
                continue;
            }
            String rightAlias = rightRef.alias() == null ? rightRef.table() : rightRef.alias();
            if (ambiguousAliases.contains(rightAlias)) continue;
            int on = findNext(tokens, index + 1, "ON");
            if (on < 0) {
                continue;
            }
            int end = clauseEnd(tokens, on + 1);
            for (int cursor = on + 1; cursor + 6 < end; cursor++) {
                FieldRef left = fieldRef(tokens, cursor, aliases, ambiguousAliases);
                if (left == null || !"=".equals(tokens.get(cursor + 3).text())) {
                    continue;
                }
                FieldRef right = fieldRef(tokens, cursor + 4, aliases, ambiguousAliases);
                if (right == null) {
                    continue;
                }
                FieldRef base;
                FieldRef joined;
                if (rightAlias.equals(left.alias()) && !rightAlias.equals(right.alias())) {
                    joined = left;
                    base = right;
                } else if (rightAlias.equals(right.alias()) && !rightAlias.equals(left.alias())) {
                    joined = right;
                    base = left;
                } else {
                    continue;
                }
                String key = base.table() + "." + base.field() + "->"
                        + joined.table() + "." + joined.field();
                if (seen.add(key)) {
                    edges.add(new JoinEdge(
                            base.table(), base.field(), joined.table(), joined.field()));
                }
                break;
            }
        }
        return edges;
    }

    private static FieldRef fieldRef(
            List<Token> tokens,
            int index,
            Map<String, String> aliases,
            Set<String> ambiguousAliases) {
        if (index + 2 >= tokens.size()
                || !tokens.get(index).identifier()
                || !".".equals(tokens.get(index + 1).text())
                || !tokens.get(index + 2).identifier()) {
            return null;
        }
        String alias = upper(tokens.get(index).text());
        if (ambiguousAliases.contains(alias)) return null;
        String table = aliases.get(alias);
        return table == null ? null
                : new FieldRef(alias, table, upper(tokens.get(index + 2).text()));
    }

    private static int findNext(List<Token> tokens, int start, String expected) {
        int depth = 0;
        for (int index = start; index < tokens.size(); index++) {
            String value = upper(tokens.get(index).text());
            if ("(".equals(value)) depth++;
            if (")".equals(value)) depth--;
            if (depth == 0 && expected.equals(value)) return index;
            if (depth == 0 && ("JOIN".equals(value) || "WHERE".equals(value))) return -1;
        }
        return -1;
    }

    private static int clauseEnd(List<Token> tokens, int start) {
        int depth = 0;
        for (int index = start; index < tokens.size(); index++) {
            String value = upper(tokens.get(index).text());
            if ("(".equals(value)) depth++;
            if (")".equals(value)) depth--;
            if (depth <= 0 && Set.of("JOIN", "WHERE", "GROUP", "ORDER", "HAVING", "UNION")
                    .contains(value)) {
                return index;
            }
        }
        return tokens.size();
    }

    private static TableRef tableRef(List<Token> tokens, int start, Set<String> ctes) {
        int index = start;
        if (index >= tokens.size() || "(".equals(tokens.get(index).text())
                || !tokens.get(index).identifier()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(upper(tokens.get(index).text()));
        index++;
        while (index + 1 < tokens.size() && ".".equals(tokens.get(index).text())
                && tokens.get(index + 1).identifier()) {
            parts.add(upper(tokens.get(index + 1).text()));
            index += 2;
        }
        if (index < tokens.size() && "(".equals(tokens.get(index).text())) {
            return null; // 表值函数或派生表达式
        }
        String table = parts.get(parts.size() - 1);
        if (ctes.contains(table)) {
            return null;
        }
        index = skipTableHint(tokens, index);
        if (index < tokens.size() && "AS".equals(upper(tokens.get(index).text()))) {
            index++;
        }
        String alias = null;
        if (index < tokens.size() && tokens.get(index).identifier()
                && !RESERVED.contains(upper(tokens.get(index).text()))) {
            alias = upper(tokens.get(index).text());
        }
        return new TableRef(table, alias);
    }

    private static int skipTableHint(List<Token> tokens, int start) {
        int index = start;
        if (index < tokens.size() && "WITH".equals(upper(tokens.get(index).text()))) {
            index++;
        }
        if (index < tokens.size() && "(".equals(tokens.get(index).text())) {
            int close = matchingClose(tokens, index);
            if (close > index) return close + 1;
        }
        return index;
    }

    private static Set<String> collectCtes(List<Token> tokens) {
        Set<String> result = new LinkedHashSet<>();
        for (int start = 0; start < tokens.size(); start++) {
            if (!"WITH".equals(upper(tokens.get(start).text()))) continue;
            int index = start + 1;
            while (index < tokens.size() && tokens.get(index).identifier()) {
                String name = upper(tokens.get(index).text());
                int cursor = index + 1;
                if (cursor < tokens.size() && "(".equals(tokens.get(cursor).text())) {
                    int close = matchingClose(tokens, cursor);
                    if (close < 0) break;
                    cursor = close + 1;
                }
                if (cursor + 1 >= tokens.size()
                        || !"AS".equals(upper(tokens.get(cursor).text()))
                        || !"(".equals(tokens.get(cursor + 1).text())) {
                    break; // WITH (NOLOCK) 等表提示
                }
                result.add(name);
                int close = matchingClose(tokens, cursor + 1);
                if (close < 0 || close + 1 >= tokens.size()
                        || !",".equals(tokens.get(close + 1).text())) {
                    break;
                }
                index = close + 2;
            }
        }
        return result;
    }

    private static int matchingClose(List<Token> tokens, int open) {
        int depth = 0;
        for (int index = open; index < tokens.size(); index++) {
            if ("(".equals(tokens.get(index).text())) depth++;
            if (")".equals(tokens.get(index).text()) && --depth == 0) return index;
        }
        return -1;
    }

    private static List<Token> tokenize(String sql) {
        List<Token> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(sql);
        while (matcher.find()) {
            String value = matcher.group(1) == null ? matcher.group() : matcher.group(1);
            result.add(new Token(value, value.matches("[A-Za-z_][A-Za-z0-9_$#]*")
                    || matcher.group(1) != null));
        }
        return result;
    }

    private static String maskCommentsAndLiterals(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            if (index + 1 < value.length() && value.charAt(index) == '-'
                    && value.charAt(index + 1) == '-') {
                while (index < value.length() && value.charAt(index) != '\n') {
                    result.append(' ');
                    index++;
                }
                continue;
            }
            if (index + 1 < value.length() && value.charAt(index) == '/'
                    && value.charAt(index + 1) == '*') {
                result.append("  ");
                index += 2;
                while (index + 1 < value.length()
                        && !(value.charAt(index) == '*' && value.charAt(index + 1) == '/')) {
                    result.append(value.charAt(index++) == '\n' ? '\n' : ' ');
                }
                if (index + 1 < value.length()) {
                    result.append("  ");
                    index += 2;
                }
                continue;
            }
            if (value.charAt(index) == '\'') {
                result.append(' ');
                index++;
                while (index < value.length()) {
                    char item = value.charAt(index++);
                    result.append(item == '\n' ? '\n' : ' ');
                    if (item == '\'' && (index >= value.length() || value.charAt(index) != '\'')) break;
                    if (item == '\'' && index < value.length()) {
                        result.append(' ');
                        index++;
                    }
                }
                continue;
            }
            result.append(value.charAt(index++));
        }
        return result.toString();
    }

    private static String stripSqlEnvelope(String value) {
        String result = value.strip();
        if (result.startsWith("\"'") && result.endsWith("'\"") && result.length() > 4) {
            return result.substring(2, result.length() - 2).strip();
        }
        if (result.startsWith("'") && result.length() > 1) {
            result = result.substring(1).strip();
            if (result.endsWith("'")) result = result.substring(0, result.length() - 1).strip();
            return result;
        }
        return result;
    }

    private static Map<String, List<String>> immutableFields(Map<String, Set<String>> fields) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        fields.forEach((table, values) -> result.put(table, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private record Token(String text, boolean identifier) {}
    private record TableRef(String table, String alias) {}
    private record FieldRef(String alias, String table, String field) {}
    private record AliasCatalog(
            Map<String, String> aliases,
            Set<String> tables,
            Set<String> ambiguousAliases) {}

    public record JoinEdge(
            String baseTable, String baseField, String joinedTable, String joinedField) {}

    public record SqlLineage(
            List<String> tables,
            Map<String, List<String>> fieldsByTable,
            List<JoinEdge> joins,
            List<String> ctes,
            List<String> warnings) {
        public boolean certain() {
            return !tables.isEmpty() && warnings.stream()
                    .noneMatch(value -> value.contains("未能识别") || value.contains("模板"));
        }
    }
}

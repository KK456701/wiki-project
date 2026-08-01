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
            "\\[([^]]+)]|[A-Za-z_][A-Za-z0-9_$#]*|\\.|,|\\(|\\)|=|\\*");
    private static final Set<String> RESERVED = Set.of(
            "SELECT", "FROM", "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER",
            "CROSS", "ON", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT",
            "INTERSECT", "AS", "WITH", "NOLOCK", "AND", "OR", "WHEN", "THEN", "ELSE",
            "END", "CASE", "BY", "OFFSET", "FETCH", "FOR", "OPTION");

    public SqlLineage analyze(String sql) {
        String source = stripSqlEnvelope(sql == null ? "" : sql);
        String normalized = maskCommentsAndLiterals(source);
        List<Token> tokens = tokenize(normalized);
        ScopedCatalog scoped = buildScopedCatalog(tokens);
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        Map<String, Map<String, Set<FieldRole>>> roles = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (int index = 0; index + 2 < tokens.size(); index++) {
            if (!qualifiedFieldAt(tokens, index)) continue;
            String alias = upper(tokens.get(index).text());
            String field = upper(tokens.get(index + 2).text());
            if ("*".equals(field)) continue;
            ResolvedField resolved = resolveField(
                    scoped.scopeAt(index), alias, field, scoped, new LinkedHashSet<>());
            if (resolved == null) {
                if (scoped.hasRelation(scoped.scopeAt(index), alias)) {
                    unresolved.add(scoped.relationPath(scoped.scopeAt(index), alias)
                            + " / 输出字段 " + alias + "." + field + " 无法追溯");
                }
                continue;
            }
            fields.computeIfAbsent(resolved.table(), ignored -> new LinkedHashSet<>())
                    .add(resolved.field());
            roles.computeIfAbsent(resolved.table(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(resolved.field(), ignored -> new LinkedHashSet<>())
                    .addAll(fieldRoles(tokens, index, scoped));
        }

        List<JoinEdge> joins = collectScopedJoins(tokens, scoped, unresolved);
        Set<String> tables = scoped.tables();
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
        unresolved.stream().limit(20).forEach(warnings::add);
        if (unresolved.size() > 20) warnings.add("另有 " + (unresolved.size() - 20) + " 个输出字段无法追溯");
        return new SqlLineage(
                List.copyOf(tables), immutableFields(fields), immutableRoles(roles),
                List.copyOf(joins), List.copyOf(scoped.ctes()), List.copyOf(warnings),
                List.copyOf(unresolved));
    }

    private static ScopedCatalog buildScopedCatalog(List<Token> tokens) {
        int[] depth = new int[tokens.size()];
        int[] scopeAt = new int[tokens.size()];
        Map<Integer, Integer> activeAtDepth = new LinkedHashMap<>();
        Map<Integer, QueryScope> scopes = new LinkedHashMap<>();
        int currentDepth = 0;
        int nextScope = 0;
        for (int index = 0; index < tokens.size(); index++) {
            String value = upper(tokens.get(index).text());
            if (")".equals(value)) {
                int closingDepth = currentDepth;
                activeAtDepth.keySet().removeIf(item -> item >= closingDepth);
                currentDepth = Math.max(0, currentDepth - 1);
            }
            depth[index] = currentDepth;
            if ("SELECT".equals(value)) {
                int parent = nearestScope(activeAtDepth, currentDepth - 1);
                int id = ++nextScope;
                scopes.put(id, new QueryScope(id, parent, currentDepth, index,
                        "查询块 SELECT@" + index, new LinkedHashMap<>(), new LinkedHashMap<>(),
                        new LinkedHashSet<>()));
                activeAtDepth.put(currentDepth, id);
            }
            scopeAt[index] = nearestScope(activeAtDepth, currentDepth);
            if ("(".equals(value)) currentDepth++;
        }

        Map<String, Integer> cteScopes = new LinkedHashMap<>();
        for (int index = 0; index + 3 < tokens.size(); index++) {
            if (!"WITH".equals(upper(tokens.get(index).text()))) continue;
            int cursor = index + 1;
            while (cursor + 2 < tokens.size() && tokens.get(cursor).identifier()) {
                String name = upper(tokens.get(cursor).text());
                int as = cursor + 1;
                if (as < tokens.size() && "(".equals(tokens.get(as).text())) {
                    int closeColumns = matchingClose(tokens, as);
                    if (closeColumns < 0) break;
                    as = closeColumns + 1;
                }
                if (as + 1 >= tokens.size() || !"AS".equals(upper(tokens.get(as).text()))
                        || !"(".equals(tokens.get(as + 1).text())) break;
                int select = findToken(tokens, as + 2, matchingClose(tokens, as + 1), "SELECT");
                if (select >= 0 && scopeAt[select] > 0) {
                    cteScopes.put(name, scopeAt[select]);
                    QueryScope scope = scopes.get(scopeAt[select]);
                    scope.label("CTE " + name);
                }
                int close = matchingClose(tokens, as + 1);
                if (close < 0 || close + 1 >= tokens.size()
                        || !",".equals(tokens.get(close + 1).text())) break;
                cursor = close + 2;
            }
        }

        Set<String> tables = new LinkedHashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            String keyword = upper(tokens.get(index).text());
            if (!"FROM".equals(keyword) && !"JOIN".equals(keyword)) continue;
            int scopeId = scopeAt[index];
            QueryScope scope = scopes.get(scopeId);
            if (scope == null || index + 1 >= tokens.size()) continue;
            int start = index + 1;
            if ("(".equals(tokens.get(start).text())) {
                int close = matchingClose(tokens, start);
                int select = findToken(tokens, start + 1, close, "SELECT");
                String alias = close < 0 ? null : aliasAfter(tokens, close + 1);
                if (select >= 0 && alias != null) {
                    int targetScope = scopeAt[select];
                    scope.relations().put(alias, Relation.derived(targetScope));
                    QueryScope child = scopes.get(targetScope);
                    if (child != null) child.label("派生表 " + alias);
                }
                continue;
            }
            if (!tokens.get(start).identifier()) continue;
            String name = upper(tokens.get(start).text());
            Integer cteScope = cteScopes.get(name);
            if (cteScope != null) {
                String alias = aliasAfter(tokens, start + 1);
                scope.relations().put(alias == null ? name : alias, Relation.derived(cteScope));
                continue;
            }
            TableRef ref = tableRef(tokens, start, cteScopes.keySet());
            if (ref == null) continue;
            tables.add(ref.table());
            scope.relations().put(ref.table(), Relation.physical(ref.table()));
            if (ref.alias() != null) scope.relations().put(ref.alias(), Relation.physical(ref.table()));
        }

        ScopedCatalog catalog = new ScopedCatalog(tokens, depth, scopeAt, scopes, tables,
                new LinkedHashSet<>(cteScopes.keySet()));
        for (QueryScope scope : scopes.values()) collectProjection(scope, catalog);
        return catalog;
    }

    private static void collectProjection(QueryScope scope, ScopedCatalog catalog) {
        List<Token> tokens = catalog.tokens();
        int from = topLevelKeyword(tokens, scope, scope.selectIndex() + 1, "FROM", catalog);
        if (from < 0) return;
        int segmentStart = scope.selectIndex() + 1;
        for (int index = segmentStart; index <= from; index++) {
            boolean split = index == from || (",".equals(tokens.get(index).text())
                    && catalog.depth()[index] == scope.depth());
            if (!split) continue;
            addProjectionSegment(scope, tokens, segmentStart, index, catalog);
            segmentStart = index + 1;
        }
    }

    private static void addProjectionSegment(
            QueryScope scope, List<Token> tokens, int start, int end, ScopedCatalog catalog) {
        if (start >= end) return;
        String output = null;
        for (int index = start; index + 1 < end; index++) {
            if ("AS".equals(upper(tokens.get(index).text())) && tokens.get(index + 1).identifier()) {
                output = upper(tokens.get(index + 1).text());
            }
        }
        int sourceIndex = -1;
        int sourceCount = 0;
        for (int index = start; index + 2 < end; index++) {
            if (qualifiedFieldAt(tokens, index)) {
                sourceIndex = index;
                sourceCount++;
            }
        }
        if (sourceCount != 1) {
            if (output != null) scope.declaredOutputs().add(output);
            return;
        }
        if (output == null) output = upper(tokens.get(sourceIndex + 2).text());
        scope.declaredOutputs().add(output);
        ResolvedField resolved = resolveField(scope.id(), upper(tokens.get(sourceIndex).text()),
                upper(tokens.get(sourceIndex + 2).text()), catalog, new LinkedHashSet<>());
        if (resolved != null) scope.projections().put(output, resolved);
    }

    private static ResolvedField resolveField(
            int scopeId, String alias, String field, ScopedCatalog catalog, Set<String> visiting) {
        int current = scopeId;
        while (current > 0) {
            QueryScope scope = catalog.scopes().get(current);
            if (scope == null) break;
            Relation relation = scope.relations().get(alias);
            if (relation != null) {
                if (relation.table() != null) return new ResolvedField(relation.table(), field);
                String key = relation.scopeId() + ":" + field;
                if (!visiting.add(key)) return null;
                QueryScope derived = catalog.scopes().get(relation.scopeId());
                if (derived == null) return null;
                ResolvedField exact = derived.projections().get(field);
                if (exact != null) return exact;
                if (derived.declaredOutputs().contains(field)) return null;
                ResolvedField wildcard = derived.projections().get("*");
                return wildcard == null ? null : new ResolvedField(wildcard.table(), field);
            }
            current = scope.parentId();
        }
        return null;
    }

    private static List<JoinEdge> collectScopedJoins(
            List<Token> tokens, ScopedCatalog catalog, Set<String> unresolved) {
        List<JoinEdge> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!"JOIN".equals(upper(tokens.get(index).text()))) continue;
            int on = findScopedKeyword(tokens, index + 1, "ON", catalog, catalog.scopeAt(index));
            if (on < 0) continue;
            int end = scopedClauseEnd(tokens, on + 1, catalog, catalog.scopeAt(index));
            for (int cursor = on + 1; cursor + 6 < end; cursor++) {
                if (!qualifiedFieldAt(tokens, cursor) || !"=".equals(tokens.get(cursor + 3).text())
                        || !qualifiedFieldAt(tokens, cursor + 4)) continue;
                ResolvedField left = resolveField(catalog.scopeAt(cursor),
                        upper(tokens.get(cursor).text()), upper(tokens.get(cursor + 2).text()),
                        catalog, new LinkedHashSet<>());
                ResolvedField right = resolveField(catalog.scopeAt(cursor + 4),
                        upper(tokens.get(cursor + 4).text()), upper(tokens.get(cursor + 6).text()),
                        catalog, new LinkedHashSet<>());
                if (left == null || right == null) {
                    unresolved.add(catalog.path(catalog.scopeAt(cursor)) + " / JOIN 关联字段无法追溯");
                    continue;
                }
                String key = left.table() + "." + left.field() + "->" + right.table() + "." + right.field();
                if (seen.add(key)) result.add(new JoinEdge(
                        left.table(), left.field(), right.table(), right.field()));
                break;
            }
        }
        return result;
    }

    private static Set<FieldRole> fieldRoles(
            List<Token> tokens, int index, ScopedCatalog catalog) {
        QueryScope scope = catalog.scopes().get(catalog.scopeAt(index));
        if (scope == null) return Set.of(FieldRole.SELECT_ONLY);
        String clause = "SELECT";
        for (int cursor = scope.selectIndex() + 1; cursor < index; cursor++) {
            if (catalog.scopeAt(cursor) != scope.id() || catalog.depth()[cursor] != scope.depth()) continue;
            String token = upper(tokens.get(cursor).text());
            if (Set.of("FROM", "ON", "WHERE", "GROUP", "HAVING", "ORDER").contains(token)) clause = token;
        }
        Set<FieldRole> result = new LinkedHashSet<>();
        String field = upper(tokens.get(index + 2).text());
        if ("ON".equals(clause)) result.add(FieldRole.JOIN_KEY);
        else if ("GROUP".equals(clause)) result.add(FieldRole.GROUP_KEY);
        else if ("WHERE".equals(clause) || "HAVING".equals(clause)) {
            result.add(isTimeField(field) ? FieldRole.TIME_FILTER : FieldRole.DENOMINATOR_SCOPE);
        } else {
            result.add(FieldRole.SELECT_ONLY);
            if (containsBefore(tokens, scope.selectIndex(), index, "DISTINCT")) result.add(FieldRole.DISTINCT_KEY);
            if (containsBefore(tokens, Math.max(scope.selectIndex(), index - 20), index, "CASE")
                    && (containsBefore(tokens, Math.max(scope.selectIndex(), index - 30), index, "COUNT")
                        || containsBefore(tokens, Math.max(scope.selectIndex(), index - 30), index, "SUM"))) {
                result.add(FieldRole.NUMERATOR_CONDITION);
                result.remove(FieldRole.SELECT_ONLY);
            }
        }
        return Set.copyOf(result);
    }

    private static boolean containsBefore(List<Token> tokens, int start, int end, String value) {
        for (int index = start; index < end; index++) {
            if (value.equals(upper(tokens.get(index).text()))) return true;
        }
        return false;
    }

    private static boolean isTimeField(String field) {
        return field.endsWith("_AT") || field.contains("TIME") || field.contains("DATE")
                || field.contains("DATETIME") || field.contains("CREATED") || field.contains("MODIFIED");
    }

    private static boolean qualifiedFieldAt(List<Token> tokens, int index) {
        if (index + 2 >= tokens.size() || !tokens.get(index).identifier()
                || !".".equals(tokens.get(index + 1).text())) return false;
        String alias = upper(tokens.get(index).text());
        String field = upper(tokens.get(index + 2).text());
        return !RESERVED.contains(alias) && ("*".equals(field)
                || tokens.get(index + 2).identifier() && !RESERVED.contains(field));
    }

    private static int nearestScope(Map<Integer, Integer> active, int depth) {
        for (int current = depth; current >= 0; current--) {
            Integer value = active.get(current);
            if (value != null) return value;
        }
        return 0;
    }

    private static int findToken(List<Token> tokens, int start, int end, String expected) {
        if (end < 0) end = tokens.size();
        for (int index = start; index < end; index++) {
            if (expected.equals(upper(tokens.get(index).text()))) return index;
        }
        return -1;
    }

    private static int topLevelKeyword(
            List<Token> tokens, QueryScope scope, int start, String expected, ScopedCatalog catalog) {
        for (int index = start; index < tokens.size(); index++) {
            if (catalog.scopeAt(index) == scope.id() && catalog.depth()[index] == scope.depth()
                    && expected.equals(upper(tokens.get(index).text()))) return index;
            if (catalog.scopeAt(index) != scope.id() && catalog.depth()[index] < scope.depth()) break;
        }
        return -1;
    }

    private static int findScopedKeyword(
            List<Token> tokens, int start, String expected, ScopedCatalog catalog, int scopeId) {
        for (int index = start; index < tokens.size(); index++) {
            if (catalog.scopeAt(index) == scopeId && expected.equals(upper(tokens.get(index).text()))) return index;
            if (catalog.scopeAt(index) == scopeId && Set.of("JOIN", "WHERE").contains(upper(tokens.get(index).text()))) return -1;
        }
        return -1;
    }

    private static int scopedClauseEnd(
            List<Token> tokens, int start, ScopedCatalog catalog, int scopeId) {
        for (int index = start; index < tokens.size(); index++) {
            if (catalog.scopeAt(index) == scopeId && Set.of("JOIN", "WHERE", "GROUP", "ORDER", "HAVING", "UNION")
                    .contains(upper(tokens.get(index).text()))) return index;
        }
        return tokens.size();
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

    private static Map<String, Map<String, List<FieldRole>>> immutableRoles(
            Map<String, Map<String, Set<FieldRole>>> roles) {
        Map<String, Map<String, List<FieldRole>>> result = new LinkedHashMap<>();
        roles.forEach((table, fields) -> {
            Map<String, List<FieldRole>> fieldRoles = new LinkedHashMap<>();
            fields.forEach((field, values) -> fieldRoles.put(field, List.copyOf(values)));
            result.put(table, Map.copyOf(fieldRoles));
        });
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

    public enum FieldRole {
        TIME_FILTER,
        NUMERATOR_CONDITION,
        DENOMINATOR_SCOPE,
        JOIN_KEY,
        GROUP_KEY,
        DISTINCT_KEY,
        SELECT_ONLY
    }

    private record Relation(String table, int scopeId) {
        static Relation physical(String table) { return new Relation(table, 0); }
        static Relation derived(int scopeId) { return new Relation(null, scopeId); }
    }

    private record ResolvedField(String table, String field) {}

    private static final class QueryScope {
        private final int id;
        private final int parentId;
        private final int depth;
        private final int selectIndex;
        private String label;
        private final Map<String, Relation> relations;
        private final Map<String, ResolvedField> projections;
        private final Set<String> declaredOutputs;

        private QueryScope(
                int id, int parentId, int depth, int selectIndex, String label,
                Map<String, Relation> relations, Map<String, ResolvedField> projections,
                Set<String> declaredOutputs) {
            this.id = id;
            this.parentId = parentId;
            this.depth = depth;
            this.selectIndex = selectIndex;
            this.label = label;
            this.relations = relations;
            this.projections = projections;
            this.declaredOutputs = declaredOutputs;
        }

        int id() { return id; }
        int parentId() { return parentId; }
        int depth() { return depth; }
        int selectIndex() { return selectIndex; }
        String label() { return label; }
        void label(String value) { this.label = value; }
        Map<String, Relation> relations() { return relations; }
        Map<String, ResolvedField> projections() { return projections; }
        Set<String> declaredOutputs() { return declaredOutputs; }
    }

    private record ScopedCatalog(
            List<Token> tokens,
            int[] depth,
            int[] scopeByToken,
            Map<Integer, QueryScope> scopes,
            Set<String> tables,
            Set<String> ctes) {
        int scopeAt(int index) {
            return index >= 0 && index < scopeByToken.length ? scopeByToken[index] : 0;
        }

        boolean hasRelation(int scopeId, String alias) {
            int current = scopeId;
            while (current > 0) {
                QueryScope scope = scopes.get(current);
                if (scope == null) return false;
                if (scope.relations().containsKey(alias)) return true;
                current = scope.parentId();
            }
            return false;
        }

        String relationPath(int scopeId, String alias) {
            int current = scopeId;
            while (current > 0) {
                QueryScope scope = scopes.get(current);
                if (scope == null) break;
                Relation relation = scope.relations().get(alias);
                if (relation != null && relation.scopeId() > 0) {
                    return path(relation.scopeId());
                }
                if (relation != null) return path(scopeId);
                current = scope.parentId();
            }
            return path(scopeId);
        }

        String path(int scopeId) {
            List<String> values = new ArrayList<>();
            int current = scopeId;
            while (current > 0) {
                QueryScope scope = scopes.get(current);
                if (scope == null) break;
                values.add(0, scope.label());
                current = scope.parentId();
            }
            return values.isEmpty() ? "SQL" : String.join(" / ", values);
        }
    }

    public record SqlLineage(
            List<String> tables,
            Map<String, List<String>> fieldsByTable,
            Map<String, Map<String, List<FieldRole>>> fieldRolesByTable,
            List<JoinEdge> joins,
            List<String> ctes,
            List<String> warnings,
            List<String> unresolvedReferences) {
        public boolean certain() {
            return !tables.isEmpty() && warnings.stream()
                    .noneMatch(value -> value.contains("未能识别") || value.contains("模板"));
        }

        public List<FieldRole> roles(String table, String field) {
            return fieldRolesByTable.getOrDefault(upper(table), Map.of())
                    .getOrDefault(upper(field), List.of(FieldRole.SELECT_ONLY));
        }
    }
}

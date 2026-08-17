package com.hospital.wikiagent.agent.diagnosis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;
import com.hospital.wikiagent.agent.mras.EntityPageData;
import com.hospital.wikiagent.agent.mras.EntityPageParser;
import com.hospital.wikiagent.agent.mras.MrasSqlExecutionService;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;

/**
 * 对实施人员上传的筛选 SQL 或完整候选 SQL 做确定性分析和改写。
 *
 * <p>模型不参与数据库、节点和关联键的选择。只有可以由当前口径 SQL
 * 证明的表、稳定键和单一节点才会生成候选；其余情况一律停止并要求人工确认。</p>
 */
@Component
public class DiagnosisSqlRepairService {
    private static final int MAX_UPLOAD_BYTES = 256 * 1024;
    private static final List<String> STABLE_KEYS = List.of(
            "ENCOUNTER_ID", "BIZ_ID", "EVENT_ID", "ORDER_ID", "CLI_ORDER_ID", "SURGERY_ID");
    private static final Pattern FILE_TYPE = Pattern.compile("(?i)^.*\\.(?:sql|txt)$");
    private static final Pattern SELECT_ALIAS = Pattern.compile(
            "(?i)\\bAS\\s+(?:\\[([^]]+)]|\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))");

    private final EntityPageParser entities;
    private final ReadOnlySqlValidator validator;
    private final MrasSqlLineageAnalyzer lineage;

    public DiagnosisSqlRepairService(
            EntityPageParser entities,
            ReadOnlySqlValidator validator,
            MrasSqlLineageAnalyzer lineage) {
        this.entities = entities;
        this.validator = validator;
        this.lineage = lineage;
    }

    public Map<String, Object> options(DiagnosisCaseSnapshot snapshot) {
        EntityPageData entity = entity(snapshot);
        List<Map<String, Object>> availableNodes = nodes(entity);
        boolean sourceAvailable = availableNodes.stream()
                .anyMatch(node -> "SOURCE_EXTRACT".equals(node.get("layer")));
        List<Map<String, Object>> ruleNodes = availableNodes.stream()
                .filter(node -> sourceAvailable
                        ? "SOURCE_EXTRACT".equals(node.get("layer"))
                        : !"SOURCE_EXTRACT".equals(node.get("layer")))
                .toList();
        Set<String> searchable = new LinkedHashSet<>();
        ruleNodes.forEach(node -> {
            String sql = text(node.get("sql")).toUpperCase(Locale.ROOT);
            if (containsAny(sql, "FULL_NAME", "PATIENT_NAME", "IMRN", "ENCOUNTER_ID")) {
                searchable.add("PATIENT_SCOPE");
            }
            if (containsAny(sql, "DEPT", "WARD", "ORG_ID", "ORGANIZATION")) {
                searchable.add("DEPARTMENT_SCOPE");
            }
            if (containsAny(sql, "ADMITTED", "DISCHARGED", "OCCURRED_AT", "START_TIME", "END_TIME")) {
                searchable.add("TIME_RANGE");
            }
            if (containsAny(sql, "IS_DEL", "STATUS_CODE", "STATE_CODE")) {
                searchable.add("STATUS_AND_DELETE_FLAG");
            }
            if (containsAny(sql, "ADMITTED", "DISCHARGED")) searchable.add("STAY_DURATION");
            if (containsAny(sql, "CONSULT")) searchable.add("CONSULTATION_STATUS");
            if (containsAny(sql, "SURGERY", "OPERATION", "OPERAT")) searchable.add("SURGERY_LEVEL");
        });
        List<Map<String, Object>> rules = searchable.stream().map(value -> Map.<String, Object>of(
                "key", value,
                "label", ruleLabel(value),
                "available", true)).toList();
        return Map.of(
                "ruleId", snapshot.ruleId(),
                "profileId", snapshot.profileId(),
                "actions", List.of("AI_GENERATE_SQL", "UPLOAD_SQL"),
                "recommendedLayer", sourceAvailable ? "SOURCE_EXTRACT" : "OVERVIEW",
                "uploadModes", List.of(
                        Map.of("mode", "FILTER_SQL", "membership", "INCLUDE", "tone", "BLUE"),
                        Map.of("mode", "FILTER_SQL", "membership", "EXCLUDE", "tone", "ORANGE"),
                        Map.of("mode", "FULL_CANDIDATE_SQL", "tone", "NEUTRAL")),
                "uploadExamples", uploadExamples(entity, availableNodes),
                "rules", rules,
                "nodes", availableNodes.stream().map(this::publicNode).toList());
    }

    private List<Map<String, Object>> uploadExamples(
            EntityPageData entity, List<Map<String, Object>> availableNodes) {
        Map<String, Object> target = availableNodes.stream()
                .filter(node -> "SOURCE_EXTRACT".equals(node.get("layer")))
                .findFirst()
                .orElseGet(() -> availableNodes.stream()
                        .filter(node -> "OVERVIEW".equals(node.get("sqlKind")))
                        .findFirst().orElse(Map.of()));
        if (target.isEmpty()) return List.of();
        String sql = text(target.get("sql"));
        String key = STABLE_KEYS.stream().filter(value -> containsKey(sql, value))
                .findFirst().orElse("ENCOUNTER_ID");
        List<String> tables = lineage.analyze(sql).tables();
        String table = tables.isEmpty()
                ? (text(entity.targetTable()).isBlank()
                        ? "CURRENT_INDICATOR_SOURCE" : text(entity.targetTable()))
                : tables.get(0);
        String database = text(target.get("database"));
        String dialect = text(target.get("dialect"));
        String nodeId = text(target.get("nodeId"));
        String filterSql = "SELECT t." + key + "\nFROM " + table + " t\n"
                + "WHERE t." + key + " IS NOT NULL\n"
                + "  /* 在这里补充需要核查的患者、科室或业务条件 */";
        return List.of(
                uploadExample("FILTER_SQL", "EXCLUDE", "排查患者或科室示例",
                        filterSql, database, dialect, nodeId),
                uploadExample("FILTER_SQL", "INCLUDE", "新增患者或科室示例",
                        filterSql, database, dialect, nodeId),
                uploadExample("FULL_CANDIDATE_SQL", "", "完整候选 SQL 示例",
                        sql, database, dialect, nodeId));
    }

    private static Map<String, Object> uploadExample(
            String mode, String membership, String title, String sql,
            String database, String dialect, String nodeId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", mode);
        if (!membership.isBlank()) value.put("membership", membership);
        value.put("title", title);
        value.put("sqlText", sql);
        value.put("database", database);
        value.put("dialect", dialect);
        value.put("targetNodeId", nodeId);
        return Map.copyOf(value);
    }

    public Map<String, Object> analyze(
            DiagnosisCaseSnapshot snapshot, Map<String, Object> request) {
        String mode = enumValue(request.get("mode"), "FILTER_SQL", "FULL_CANDIDATE_SQL");
        String membership = text(request.get("membership")).toUpperCase(Locale.ROOT);
        if ("FILTER_SQL".equals(mode) && !List.of("INCLUDE", "EXCLUDE").contains(membership)) {
            throw new IllegalArgumentException("筛选 SQL 必须先选择返回记录应纳入或应排除");
        }
        if ("FULL_CANDIDATE_SQL".equals(mode)) membership = "";
        String fileName = text(request.get("fileName"));
        if (!fileName.isBlank() && !FILE_TYPE.matcher(fileName).matches()) {
            throw new IllegalArgumentException("仅支持 .sql 或 .txt 文件");
        }
        String sql = canonical(request.get("sqlText"));
        if (sql.isBlank()) throw new IllegalArgumentException("请粘贴或上传 SQL");
        if (sql.getBytes(StandardCharsets.UTF_8).length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("上传 SQL 不能超过 256KB");
        }
        var validation = validator.validateAdHocReadOnly(sql);
        if (!validation.ok()) throw new IllegalArgumentException(validation.message());

        EntityPageData entity = entity(snapshot);
        List<Map<String, Object>> availableNodes = nodes(entity);
        Set<String> uploadedTables = upperSet(lineage.analyze(sql).tables());
        if (uploadedTables.isEmpty()) throw new IllegalArgumentException("无法识别上传 SQL 引用的物理表");
        Set<String> businessTables = businessTables(entity, availableNodes);
        Set<String> statisticsTables = statisticsTables(entity, availableNodes);
        boolean businessMatch = intersects(uploadedTables, businessTables);
        Set<String> statisticsOnlyTables = new LinkedHashSet<>(statisticsTables);
        statisticsOnlyTables.removeAll(businessTables);
        boolean statisticsMatch = intersects(uploadedTables, statisticsOnlyTables);
        if (businessMatch && statisticsMatch) {
            throw new IllegalArgumentException("上传 SQL 同时引用 Oracle 业务表和 SQL Server 中间表，禁止跨库拼接");
        }
        String database = statisticsMatch || entity.sourceQueryFromReal() ? "SQL_SERVER" : "ORACLE";
        String dialect = "SQL_SERVER".equals(database) ? "SQL Server" : "Oracle";

        Set<String> knownTables = new LinkedHashSet<>(businessTables);
        knownTables.addAll(statisticsTables);
        Set<String> newDependencies = new LinkedHashSet<>(uploadedTables);
        newDependencies.removeAll(knownTables);
        String matchKey = stableKey(sql, availableNodes);
        List<String> candidateNodeIds;
        String recommendedLayer;
        if ("FILTER_SQL".equals(mode)) {
            if (matchKey.isBlank()) {
                throw new IllegalArgumentException("筛选 SQL 必须返回 ENCOUNTER_ID 等可验证的稳定业务键");
            }
            if ("ORACLE".equals(database) && hasSource(entity)) {
                candidateNodeIds = List.of("source-extract-sql");
                recommendedLayer = "SOURCE_EXTRACT";
            } else {
                candidateNodeIds = availableNodes.stream()
                        .filter(node -> List.of("OVERVIEW", "DEPARTMENT", "PATIENT_DETAIL")
                                .contains(text(node.get("sqlKind"))))
                        .filter(node -> containsKey(text(node.get("sql")), matchKey))
                        .map(node -> text(node.get("nodeId"))).toList();
                if (candidateNodeIds.isEmpty()) {
                    throw new IllegalArgumentException("统计 SQL 没有输出稳定匹配键，无法安全应用筛选 SQL");
                }
                recommendedLayer = "STATISTICS";
            }
        } else {
            String requestedNode = text(request.get("targetNodeId"));
            List<Map<String, Object>> matches = fullCandidateMatches(sql, uploadedTables, availableNodes);
            if (!requestedNode.isBlank()) {
                matches = matches.stream().filter(node -> requestedNode.equals(node.get("nodeId"))).toList();
            }
            if (matches.isEmpty()) throw new IllegalArgumentException("完整候选 SQL 与当前指标任何 SQL 节点都不匹配");
            candidateNodeIds = matches.stream().map(node -> text(node.get("nodeId"))).toList();
            recommendedLayer = matches.size() == 1 ? text(matches.get(0).get("layer")) : "AMBIGUOUS";
        }

        boolean ambiguous = "FULL_CANDIDATE_SQL".equals(mode) && candidateNodeIds.size() != 1;
        String action = "INCLUDE".equals(membership) ? "纳入" : "排除";
        String summary = "FULL_CANDIDATE_SQL".equals(mode)
                ? (ambiguous ? "该完整候选 SQL 可匹配多个节点，请选择需要替换的节点。"
                        : "该完整候选 SQL 将替换“" + nodeLabel(candidateNodeIds.get(0)) + "”，试跑不会修改正式口径。")
                : "该 SQL 将在" + ("ORACLE".equals(database) ? " Oracle 业务库" : " SQL Server 中间库")
                        + "执行，通过 " + matchKey + " 与当前链路关联，返回记录将被" + action
                        + "；预计修改" + ("SOURCE_EXTRACT".equals(recommendedLayer) ? "源表抽取 SQL。" : "统计层相关 SQL。 ");
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("database", database);
        impact.put("dialect", dialect);
        impact.put("referencedTables", List.copyOf(uploadedTables));
        impact.put("outputFields", outputFields(sql));
        impact.put("matchKey", matchKey);
        impact.put("newDependencies", List.copyOf(newDependencies));
        impact.put("requiresDependencyConfirmation", !newDependencies.isEmpty());
        impact.put("recommendedLayer", recommendedLayer);
        impact.put("affectedNodeIds", candidateNodeIds);
        impact.put("ambiguous", ambiguous);
        return Map.of(
                "mode", mode,
                "membership", membership,
                "sqlText", sql,
                "fileName", fileName,
                "summary", summary,
                "impactAnalysis", Map.copyOf(impact),
                "validation", Map.of("ok", true, "message", validation.message()),
                "targetChoices", candidateNodeIds.stream().map(id -> Map.of(
                        "nodeId", id, "label", nodeLabel(id))).toList());
    }

    public Map<String, Object> createChangeSet(
            DiagnosisCaseSnapshot snapshot, Map<String, Object> request) {
        Map<String, Object> analysis = analyze(snapshot, request);
        Map<String, Object> impact = map(analysis.get("impactAnalysis"));
        boolean confirmed = Boolean.TRUE.equals(request.get("confirmNewDependencies"));
        if (Boolean.TRUE.equals(impact.get("requiresDependencyConfirmation")) && !confirmed) {
            throw new IllegalArgumentException("上传 SQL 引入了当前指标未使用的业务表，请确认该表属于当前指标业务口径");
        }
        if (Boolean.TRUE.equals(impact.get("ambiguous"))) {
            throw new IllegalArgumentException("完整候选 SQL 可匹配多个节点，请先选择目标节点");
        }
        EntityPageData entity = entity(snapshot);
        String mode = text(analysis.get("mode"));
        String membership = text(analysis.get("membership"));
        String uploadedSql = text(analysis.get("sqlText"));
        String matchKey = text(impact.get("matchKey"));
        List<String> nodeIds = stringList(impact.get("affectedNodeIds"));
        List<Map<String, Object>> changes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            Map<String, Object> node = node(entity, nodeId);
            String original = text(node.get("sql"));
            String candidate = "FULL_CANDIDATE_SQL".equals(mode) ? uploadedSql
                    : filterCandidate(original, uploadedSql, matchKey, membership);
            var validation = "FULL_CANDIDATE_SQL".equals(mode)
                    ? validator.validateAdHocReadOnly(candidate)
                    : validator.validateAdHocReadOnly(candidate.replace(';', ' ').replace("#", ""));
            if (!validation.ok()) throw new IllegalArgumentException(validation.message());
            changes.add(Map.of(
                    "nodeId", nodeId,
                    "sqlKind", node.get("sqlKind"),
                    "database", node.get("database"),
                    "dialect", node.get("dialect"),
                    "originalSql", original,
                    "candidateSql", candidate,
                    "originalSqlHash", DiagnosisShadowRunner.sha256(original),
                    "candidateSqlHash", DiagnosisShadowRunner.sha256(candidate),
                    "changedLines", changedLines(original, candidate),
                    "validation", Map.of("ok", true, "message", validation.message())));
        }
        Map<String, Object> primary = changes.get(0);
        String layer = text(node(entity, text(primary.get("nodeId"))).get("layer"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeSetId", "DCS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        result.put("layer", layer);
        result.put("nodeId", primary.get("nodeId"));
        result.put("sql", primary.get("candidateSql"));
        result.put("originalSql", primary.get("originalSql"));
        result.put("originalSqlHash", primary.get("originalSqlHash"));
        result.put("candidateSqlHash", primary.get("candidateSqlHash"));
        result.put("generationMode", "UPLOAD_SQL");
        result.put("generationMethod", "FULL_CANDIDATE_SQL".equals(mode)
                ? "实施人员上传完整候选 SQL" : "程序按上传筛选 SQL 确定性生成");
        result.put("impactAnalysis", impact);
        result.put("changes", List.copyOf(changes));
        result.put("overallValidation", Map.of("ok", true, "message", "全部节点安全校验通过"));
        result.put("validation", Map.of("ok", true, "message", "安全校验通过"));
        result.put("validationStages", List.of("单条只读查询检查通过", "数据库与节点路由检查通过",
                "稳定业务键关联检查通过", "新增依赖确认检查通过"));
        result.put("diffSummary", text(analysis.get("summary")));
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> nodes(EntityPageData entity) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (hasSource(entity)) result.add(nodeValue("source-extract-sql", "SOURCE_EXTRACT",
                "SOURCE_EXTRACT", entity.sourceQueryFromReal() ? "SQL_SERVER" : "ORACLE", sourceSql(entity)));
        if (!text(entity.overviewSql()).isBlank()) result.add(nodeValue("overview-sql", "OVERVIEW",
                "OVERVIEW", "SQL_SERVER", entity.overviewSql()));
        if (!text(entity.deptStatSql()).isBlank()) result.add(nodeValue("department-sql", "STATISTICS",
                "DEPARTMENT", "SQL_SERVER", entity.deptStatSql()));
        if (!text(entity.patientDetailSql()).isBlank()) result.add(nodeValue("patient-sql", "STATISTICS",
                "PATIENT_DETAIL", "SQL_SERVER", entity.patientDetailSql()));
        return List.copyOf(result);
    }

    private static Map<String, Object> nodeValue(
            String nodeId, String layer, String sqlKind, String database, String sql) {
        return Map.of("nodeId", nodeId, "layer", layer, "sqlKind", sqlKind,
                "database", database, "dialect", "ORACLE".equals(database) ? "Oracle" : "SQL Server",
                "sql", canonical(sql));
    }

    private Map<String, Object> publicNode(Map<String, Object> node) {
        return Map.of("nodeId", node.get("nodeId"), "label", nodeLabel(text(node.get("nodeId"))),
                "sqlKind", node.get("sqlKind"), "database", node.get("database"),
                "dialect", node.get("dialect"), "available", true);
    }

    private List<Map<String, Object>> fullCandidateMatches(
            String sql, Set<String> tables, List<Map<String, Object>> nodes) {
        List<Map<String, Object>> matches = nodes.stream().filter(node -> {
            Set<String> nodeTables = upperSet(lineage.analyze(text(node.get("sql"))).tables());
            return intersects(tables, nodeTables);
        }).toList();
        if (matches.size() <= 1) return matches;
        Set<String> aliases = new LinkedHashSet<>(outputFields(sql));
        List<Map<String, Object>> narrowed = matches.stream().filter(node -> {
            Set<String> nodeAliases = new LinkedHashSet<>(outputFields(text(node.get("sql"))));
            return !aliases.isEmpty() && aliases.equals(nodeAliases);
        }).toList();
        return narrowed.isEmpty() ? matches : narrowed;
    }

    private String stableKey(String sql, List<Map<String, Object>> nodes) {
        return STABLE_KEYS.stream().filter(key -> containsKey(sql, key))
                .filter(key -> nodes.stream().anyMatch(node -> containsKey(text(node.get("sql")), key)))
                .findFirst().orElse("");
    }

    private static String filterCandidate(
            String original, String filter, String key, String membership) {
        String operator = "EXCLUDE".equals(membership) ? "NOT EXISTS" : "EXISTS";
        return "SELECT DIAG_BASE.*\nFROM (\n" + trimSemicolon(original)
                + "\n) DIAG_BASE\nWHERE " + operator + " (\n"
                + "  SELECT 1\n  FROM (\n" + indent(trimSemicolon(filter), "    ")
                + "\n  ) DIAG_FILTER\n  WHERE DIAG_FILTER." + key + " = DIAG_BASE." + key + "\n)";
    }

    private Set<String> businessTables(EntityPageData entity, List<Map<String, Object>> nodes) {
        Set<String> result = new LinkedHashSet<>();
        if (entity.bizTables() != null) entity.bizTables().forEach(value -> result.add(upper(value)));
        nodes.stream().filter(node -> "SOURCE_EXTRACT".equals(node.get("layer")))
                .forEach(node -> result.addAll(upperSet(
                        lineage.analyze(text(node.get("sql"))).tables())));
        return result;
    }

    private Set<String> statisticsTables(EntityPageData entity, List<Map<String, Object>> nodes) {
        Set<String> result = new LinkedHashSet<>();
        if (!text(entity.targetTable()).isBlank()) result.add(upper(entity.targetTable()));
        nodes.stream().filter(node -> !"SOURCE_EXTRACT".equals(node.get("layer")))
                .forEach(node -> result.addAll(upperSet(lineage.analyze(text(node.get("sql"))).tables())));
        return result;
    }

    private static List<String> outputFields(String sql) {
        Set<String> fields = new LinkedHashSet<>();
        for (String key : STABLE_KEYS) if (containsKey(sql, key)) fields.add(key);
        Matcher matcher = SELECT_ALIAS.matcher(sql);
        while (matcher.find()) {
            for (int index = 1; index <= 3; index++) {
                if (matcher.group(index) != null) fields.add(matcher.group(index));
            }
        }
        return List.copyOf(fields);
    }

    private Map<String, Object> node(EntityPageData entity, String nodeId) {
        return nodes(entity).stream().filter(node -> nodeId.equals(node.get("nodeId"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前指标不存在 SQL 节点: " + nodeId));
    }

    private EntityPageData entity(DiagnosisCaseSnapshot snapshot) {
        EntityPageData entity = entities.getEntity(snapshot.profileId(), snapshot.hospitalId());
        if (entity == null) throw new IllegalArgumentException("当前生效口径不存在");
        return entity;
    }

    private static String sourceSql(EntityPageData entity) {
        return canonical(entity.sourceQueryFromReal()
                ? entity.sourceTableSql() : entity.effectiveSourceTableSql(true));
    }

    private static boolean hasSource(EntityPageData entity) {
        return !sourceSql(entity).isBlank() && !text(entity.targetTable()).isBlank();
    }

    private static int changedLines(String before, String after) {
        String[] left = before.split("\\R", -1);
        String[] right = after.split("\\R", -1);
        int count = Math.abs(left.length - right.length);
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            if (!left[index].equals(right[index])) count++;
        }
        return count;
    }

    private static String nodeLabel(String id) {
        return switch (id) {
            case "source-extract-sql" -> "源表抽取 SQL";
            case "overview-sql" -> "概览统计 SQL";
            case "department-sql" -> "科室统计 SQL";
            case "patient-sql" -> "患者明细 SQL";
            default -> id;
        };
    }

    private static String ruleLabel(String key) {
        return switch (key) {
            case "PATIENT_SCOPE" -> "患者范围";
            case "DEPARTMENT_SCOPE" -> "科室范围";
            case "TIME_RANGE" -> "时间范围";
            case "STATUS_AND_DELETE_FLAG" -> "状态码与删除标记";
            case "STAY_DURATION" -> "住院时长（支持跨字段8/24小时）";
            case "CONSULTATION_STATUS" -> "会诊状态";
            case "SURGERY_LEVEL" -> "手术等级";
            default -> key;
        };
    }

    private static boolean containsAny(String value, String... targets) {
        for (String target : targets) if (value.contains(target)) return true;
        return false;
    }

    private static boolean containsKey(String sql, String key) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(key)
                + "(?![A-Za-z0-9_])").matcher(sql).find();
    }

    private static boolean intersects(Set<String> left, Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }

    private Set<String> upperSet(List<String> values) {
        return upperSetStatic(values);
    }

    private static Set<String> upperSetStatic(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) values.forEach(value -> result.add(upper(value)));
        return result;
    }

    private static String upper(String value) {
        return text(value).replace("[", "").replace("]", "").toUpperCase(Locale.ROOT);
    }

    private static String trimSemicolon(String value) {
        return text(value).replaceFirst(";+\\s*$", "");
    }

    private static String indent(String value, String prefix) {
        return prefix + value.replace("\n", "\n" + prefix);
    }

    private static String canonical(Object value) {
        return MrasSqlExecutionService.stripLeadingTrailingQuotes(text(value));
    }

    private static String enumValue(Object value, String... allowed) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (List.of(allowed).contains(normalized)) return normalized;
        throw new IllegalArgumentException("上传 SQL 模式无效");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    private static List<String> stringList(Object value) {
        return value instanceof List<?> values ? values.stream().map(DiagnosisSqlRepairService::text).toList()
                : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}

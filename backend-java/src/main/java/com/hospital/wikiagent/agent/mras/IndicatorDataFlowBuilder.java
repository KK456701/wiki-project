package com.hospital.wikiagent.agent.mras;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.initialization.KnowledgeDataDictionary;
import com.hospital.wikiagent.agent.initialization.MrasSqlLineageAnalyzer;
import com.hospital.wikiagent.agent.mras.IndicatorDataFlowTypeResolver.FlowType;

/**
 * 根据当前生效实体 SQL 生成前端可展示的数据链路。
 *
 * <p>生成过程只做确定性的知识库结构和 SQL 血缘分析，不访问数据库，也不会用模型补写缺失表、字段或 SQL；
 * 无法证明链路类型时统一返回 {@code INCOMPLETE}，由页面显式展示缺失配置。
 */
@Component
public class IndicatorDataFlowBuilder {

    private static final String PATIENT_EVENT = "MRAS_PATIENT_EVENT";
    private static final Pattern PARAMETER = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

    private final MrasSqlLineageAnalyzer lineageAnalyzer;
    private final IndicatorDataFlowTypeResolver typeResolver;
    private final KnowledgeDataDictionary dictionary;

    public IndicatorDataFlowBuilder(
            MrasSqlLineageAnalyzer lineageAnalyzer,
            IndicatorDataFlowTypeResolver typeResolver,
            KnowledgeDataDictionary dictionary) {
        this.lineageAnalyzer = lineageAnalyzer;
        this.typeResolver = typeResolver;
        this.dictionary = dictionary;
    }

    public Map<String, Object> build(EntityPageData entity) {
        var sourceLineage = lineageAnalyzer.analyze(entity.sourceTableSql());
        FlowType flowType = typeResolver.resolve(entity);
        String templateType = flowType.name();
        String status;
        List<String> warnings = new ArrayList<>();
        if (flowType == FlowType.INCOMPLETE) {
            status = "incomplete";
            warnings.add("当前口径未配置概览 SQL，不能形成可执行统计链路。");
        } else {
            status = "complete";
            if (flowType == FlowType.DIRECT_REAL_QUERY) {
                warnings.add("本指标没有独立中间表；上游同步 SQL 未在当前指标知识库登记。");
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        if ("INCOMPLETE".equals(templateType)) {
            nodes.add(node("configuration", 1, "配置状态", "CONFIGURATION", "KNOWLEDGE",
                    List.of(), "", "", List.of(), String.join("", warnings)));
        } else if ("EVENT_TO_TARGET".equals(templateType)) {
            buildEventFlow(entity, sourceLineage.tables(), nodes, edges, warnings);
        } else if ("DIRECT_TO_TARGET".equals(templateType)) {
            buildDirectTargetFlow(entity, sourceLineage.tables(), nodes, edges);
        } else {
            buildDirectRealFlow(entity, nodes, edges);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateType", templateType);
        result.put("templateLabel", templateLabel(templateType));
        result.put("status", status);
        result.put("warnings", List.copyOf(warnings));
        result.put("nodes", List.copyOf(nodes));
        result.put("edges", List.copyOf(edges));
        return Map.copyOf(result);
    }

    private void buildEventFlow(
            EntityPageData entity,
            List<String> sourceTables,
            List<Map<String, Object>> nodes,
            List<Map<String, String>> edges,
            List<String> warnings) {
        Set<String> businessTables = new LinkedHashSet<>();
        for (var event : entity.extendedEvents()) {
            businessTables.addAll(lineageAnalyzer.analyze(event.getValue()).tables());
        }
        sourceTables.stream().filter(table -> !PATIENT_EVENT.equals(table))
                .forEach(businessTables::add);
        nodes.add(tableNode("business-tables", 1, "上游业务表", "BUSINESS",
                List.copyOf(businessTables), "拓展事件或源表 SQL实际引用的物理表。"));

        if (entity.extendedEvents().isEmpty()) {
            warnings.add("本口径复用公共事件，但未在本实体页重复登记事件生成 SQL。");
        } else {
            int index = 0;
            for (var event : entity.extendedEvents()) {
                String id = "extended-event-sql-" + (++index);
                nodes.add(sqlNode(id, nodes.size() + 1,
                        "拓展事件 SQL · " + event.getKey(), "EXTENDED_EVENT_SQL",
                        "BUSINESS", event.getValue(), "生成患者事件"));
                edge(edges, "business-tables", id, "读取");
                edge(edges, id, "patient-event", "写入");
            }
        }
        nodes.add(tableNode("patient-event", nodes.size() + 1, "患者事件表", "REAL",
                List.of(PATIENT_EVENT), "本指标源表 SQL实际读取的事件数据。"));
        if (entity.extendedEvents().isEmpty()) {
            edge(edges, "business-tables", "patient-event", "公共事件同步");
        }
        appendExtractionAndStatistics(entity, nodes, edges, "patient-event");
    }

    private void buildDirectTargetFlow(
            EntityPageData entity,
            List<String> sourceTables,
            List<Map<String, Object>> nodes,
            List<Map<String, String>> edges) {
        nodes.add(tableNode("business-tables", 1, "上游业务表", "BUSINESS",
                sourceTables, "源表 SQL实际引用的物理表。"));
        appendExtractionAndStatistics(entity, nodes, edges, "business-tables");
    }

    private void appendExtractionAndStatistics(
            EntityPageData entity,
            List<Map<String, Object>> nodes,
            List<Map<String, String>> edges,
            String upstream) {
        nodes.add(sqlNode("source-extract-sql", nodes.size() + 1, "源表抽取 SQL",
                "SOURCE_EXTRACT_SQL", "SYNC", entity.sourceTableSql(), "生成指标中间数据"));
        nodes.add(tableNode("target-table", nodes.size() + 1, "指标中间表", "REAL",
                List.of(entity.targetTable()), "抽取后供正式统计使用的真实库目标表。"));
        edge(edges, upstream, "source-extract-sql", "读取");
        edge(edges, "source-extract-sql", "target-table", "写入");
        appendStatistics(entity, nodes, edges, "target-table");
    }

    private void buildDirectRealFlow(
            EntityPageData entity,
            List<Map<String, Object>> nodes,
            List<Map<String, String>> edges) {
        Set<String> tables = new LinkedHashSet<>();
        tables.addAll(lineageAnalyzer.analyze(entity.overviewSql()).tables());
        tables.addAll(lineageAnalyzer.analyze(entity.deptStatSql()).tables());
        tables.addAll(lineageAnalyzer.analyze(entity.patientDetailSql()).tables());
        nodes.add(tableNode("real-existing-tables", 1, "真实库已有表", "REAL",
                List.copyOf(tables), "由既有同步或专题链路提供，本指标不生成独立中间表。"));
        appendStatistics(entity, nodes, edges, "real-existing-tables");
    }

    private void appendStatistics(
            EntityPageData entity,
            List<Map<String, Object>> nodes,
            List<Map<String, String>> edges,
            String upstream) {
        nodes.add(sqlNode("overview-sql", nodes.size() + 1, "概览统计 SQL",
                "OVERVIEW_SQL", "REAL", entity.overviewSql(), "生成正式指标结果"));
        edge(edges, upstream, "overview-sql", "统计");
        nodes.add(node("result", nodes.size() + 1, "指标结果", "RESULT", "REAL",
                List.of(), "", "", List.of(), "正式卡片结果。"));
        edge(edges, "overview-sql", "result", "输出");
        if (present(entity.deptStatSql())) {
            nodes.add(sqlNode("department-sql", nodes.size() + 1, "科室统计 SQL",
                    "DEPARTMENT_SQL", "REAL", entity.deptStatSql(), "按科室查看结果"));
            edge(edges, upstream, "department-sql", "统计");
        }
        if (present(entity.patientDetailSql())) {
            nodes.add(sqlNode("patient-sql", nodes.size() + 1, "患者明细 SQL",
                    "PATIENT_SQL", "REAL", entity.patientDetailSql(), "查看指标明细"));
            edge(edges, upstream, "patient-sql", "查询");
        }
    }

    private Map<String, Object> sqlNode(
            String id, int sequence, String title, String type,
            String databaseRole, String sql, String description) {
        return node(id, sequence, title, type, databaseRole,
                lineageAnalyzer.analyze(sql).tables(), type, sql, parameters(sql), description);
    }

    private Map<String, Object> tableNode(
            String id, int sequence, String title, String databaseRole,
            List<String> tables, String description) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (String table : tables == null ? List.<String>of() : tables) {
            String value = dictionary.tableDescription(table);
            if (!value.isBlank()) descriptions.put(table, value);
        }
        Map<String, Object> value = new LinkedHashMap<>(node(id, sequence, title, "TABLE", databaseRole,
                tables, "", "", List.of(), description));
        value.put("tableDescriptions", Map.copyOf(descriptions));
        return Map.copyOf(value);
    }

    private static Map<String, Object> node(
            String id, int sequence, String title, String type, String databaseRole,
            List<String> tables, String sqlKind, String sql,
            List<String> parameters, String description) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("sequence", sequence);
        value.put("title", title);
        value.put("nodeType", type);
        value.put("databaseRole", databaseRole);
        value.put("tableNames", tables == null ? List.of() : List.copyOf(tables));
        value.put("sqlKind", sqlKind);
        value.put("sql", sql == null ? "" : sql);
        value.put("parameters", parameters == null ? List.of() : List.copyOf(parameters));
        value.put("description", description);
        return Map.copyOf(value);
    }

    private static void edge(
            List<Map<String, String>> edges, String from, String to, String label) {
        edges.add(Map.of("from", from, "to", to, "label", label));
    }

    private static List<String> parameters(String sql) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = PARAMETER.matcher(sql == null ? "" : sql);
        while (matcher.find()) result.add(matcher.group(1));
        return List.copyOf(result);
    }

    private static String templateLabel(String type) {
        return switch (type) {
            case "EVENT_TO_TARGET" -> "事件表 → 指标中间表 → 统计";
            case "DIRECT_TO_TARGET" -> "业务表 → 指标中间表 → 统计";
            case "DIRECT_REAL_QUERY" -> "真实库已有表 → 直接统计";
            default -> "配置不完整";
        };
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}

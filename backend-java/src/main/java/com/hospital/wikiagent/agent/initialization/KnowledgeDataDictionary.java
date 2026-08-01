package com.hospital.wikiagent.agent.initialization;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.mras.KnowledgeIndexResources;

/**
 * 读取知识库中精简的源表索引和业务源字段字典，为初始化校验证据补充来源系统、中文字段名
 * 与业务说明。字典只影响实施页面的可读性，不参与物理依赖推导、SQL 生成或阻断判断；
 * 因此字典缺项时保留原始表字段名，不允许反向猜测数据库结构。
 */
@Component
public class KnowledgeDataDictionary {
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\|\\s*`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*\\|\\s*([^|]*)\\|\\s*([^|]*)\\|.*$");
    private static final Pattern FIELD_ROW = Pattern.compile(
            "^\\|\\s*`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*\\|\\s*`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*\\|\\s*([^|]*)\\|\\s*([^|]*)\\|.*$");

    private final Map<String, TableLabel> tables;
    private final Map<String, FieldLabel> fields;

    public KnowledgeDataDictionary(KnowledgeIndexResources resources) {
        this.tables = loadTables(resources);
        this.fields = loadFields(resources);
    }

    public String sourceSystem(String tableName) {
        TableLabel label = tables.get(upper(tableName));
        return label == null ? "未登记" : label.sourceSystem();
    }

    public String fieldLabel(String tableName, String fieldName) {
        FieldLabel label = fields.get(upper(tableName) + "." + upper(fieldName));
        return label == null ? "" : label.name();
    }

    private static Map<String, TableLabel> loadTables(KnowledgeIndexResources resources) {
        Map<String, TableLabel> result = new LinkedHashMap<>();
        for (String line : resources.read("concepts/源表索引.md").split("\\R")) {
            Matcher matcher = TABLE_ROW.matcher(line);
            if (matcher.matches() && !"源表".equals(matcher.group(1))) {
                result.put(upper(matcher.group(1)), new TableLabel(
                        matcher.group(2).strip(), matcher.group(3).strip()));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, FieldLabel> loadFields(KnowledgeIndexResources resources) {
        Map<String, FieldLabel> result = new LinkedHashMap<>();
        for (String line : resources.read("references/业务源字段字典.md").split("\\R")) {
            Matcher matcher = FIELD_ROW.matcher(line);
            if (matcher.matches() && !"源表".equals(matcher.group(1))) {
                result.put(upper(matcher.group(1)) + "." + upper(matcher.group(2)),
                        new FieldLabel(matcher.group(3).strip(), matcher.group(4).strip()));
            }
        }
        return Map.copyOf(result);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private record TableLabel(String sourceSystem, String description) {}
    private record FieldLabel(String name, String description) {}
}

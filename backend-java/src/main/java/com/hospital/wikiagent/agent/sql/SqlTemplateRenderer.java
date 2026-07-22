package com.hospital.wikiagent.agent.sql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
/**
 * 将结构化输入渲染为 {@code SqlTemplateRenderer} 所需的确定性结果。
 */
public class SqlTemplateRenderer {
    private static final Pattern IF_FIELD = Pattern.compile(
            "\\{%\\s*if\\s+fields\\.get\\('([A-Za-z0-9_]+)'\\)\\s*%}(.*?)\\{%\\s*endif\\s*%}",
            Pattern.DOTALL);
    private static final Pattern EMPTY_LOOP = Pattern.compile(
            "\\{%\\s*for\\s+dept\\s+in\\s+custom_rules\\.exclude_dept_filters\\s*%}.*?"
                    + "\\{%\\s*endfor\\s*%}", Pattern.DOTALL);
    private static final Pattern GET_OR_FIELD_COLUMN = Pattern.compile(
            "\\{\\{\\s*fields\\.get\\('([A-Za-z0-9_]+)',\\s*fields\\.([A-Za-z0-9_]+)\\)"
                    + "\\.split\\('\\.'\\)\\[-1]\\s*}}");
    private static final Pattern FIELD_COLUMN = Pattern.compile(
            "\\{\\{\\s*fields\\.([A-Za-z0-9_]+)\\.split\\('\\.'\\)\\[-1]\\s*}}");
    private static final Pattern GET_OR_LITERAL = Pattern.compile(
            "\\{\\{\\s*fields\\.get\\('([A-Za-z0-9_]+)',\\s*'([^']*)'\\)\\s*}}");
    private static final Pattern FIELD = Pattern.compile(
            "\\{\\{\\s*fields\\.([A-Za-z0-9_]+)\\s*}}");

    public String render(String template, Map<String, Object> rawFields, String mainTable) {
        Map<String, String> fields = new LinkedHashMap<>();
        rawFields.forEach((key, value) -> fields.put(key, value == null ? "" : value.toString()));
        String admitTime = fields.getOrDefault("admit_time", "");
        if (!admitTime.isBlank()) {
            fields.putIfAbsent("baseline_admit_time", admitTime);
            fields.putIfAbsent("period_time", admitTime);
        }

        String value = renderConditions(template, fields);
        value = EMPTY_LOOP.matcher(value).replaceAll("");
        value = replace(value, GET_OR_FIELD_COLUMN, match -> column(firstNonBlank(
                fields.get(match.group(1)), fields.get(match.group(2)))));
        value = replace(value, FIELD_COLUMN, match -> column(required(fields, match.group(1))));
        value = replace(value, GET_OR_LITERAL, match -> firstNonBlank(fields.get(match.group(1)), match.group(2)));
        value = replace(value, FIELD, match -> required(fields, match.group(1)));
        value = value.replaceAll("\\{\\{\\s*main_table\\s*}}", Matcher.quoteReplacement(mainTable));
        if (value.contains("{{") || value.contains("{%")) {
            throw new IllegalArgumentException("SQL 模板包含不受支持或未解析的表达式");
        }
        return value.strip();
    }

    private static String renderConditions(String template, Map<String, String> fields) {
        String value = template;
        Matcher matcher = IF_FIELD.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = fields.getOrDefault(matcher.group(1), "").isBlank() ? "" : matcher.group(2);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replace(String value, Pattern pattern, Replacer replacer) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacer.replace(matcher)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.getOrDefault(key, "").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("医院字段映射缺少 " + key);
        }
        return value;
    }

    private static String column(String value) {
        String[] parts = value.split("\\.");
        return parts[parts.length - 1];
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    @FunctionalInterface
    private interface Replacer {
        String replace(Matcher matcher);
    }
}

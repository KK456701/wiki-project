package com.hospital.wikiagent.agent.sql;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class SqlParameterBinder {
    private static final Pattern PARAMETER = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    public String bind(String sql, Map<String, Object> parameters) {
        Matcher matcher = PARAMETER.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!parameters.containsKey(name)) {
                throw new IllegalArgumentException("SQL 参数缺失: " + name);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(literal(parameters.get(name))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }
}

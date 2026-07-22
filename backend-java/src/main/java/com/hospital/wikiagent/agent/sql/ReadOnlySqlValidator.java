package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** 对生成 SQL 执行只读、单语句、危险关键字和结果规模约束校验。 */
@Component
public class ReadOnlySqlValidator {
    private static final List<String> FORBIDDEN = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "REPLACE",
            "GRANT", "REVOKE", "EXEC", "EXECUTE", "LOAD", "MERGE");

    public ValidationResult validate(String sql, String mainTable) {
        String stripped = stripComments(sql == null ? "" : sql).strip();
        String upper = stripped.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            return failure("只允许 SELECT 或 WITH...SELECT 查询");
        }
        String withoutTrailing = stripped.replaceFirst(";+\\s*$", "");
        if (withoutTrailing.contains(";")) {
            return failure("禁止多语句 SQL");
        }
        for (String keyword : FORBIDDEN) {
            if (Pattern.compile("\\b" + keyword + "\\b", Pattern.CASE_INSENSITIVE).matcher(stripped).find()) {
                return failure("禁止使用 " + keyword);
            }
        }
        if (Pattern.compile("\\bOR\\b", Pattern.CASE_INSENSITIVE).matcher(stripped).find()) {
            return failure("禁止使用 OR 条件");
        }
        if (!stripped.contains(":start_time") || !stripped.contains(":end_time")) {
            return failure("必须包含 :start_time 和 :end_time 参数");
        }
        String expected = mainTable == null ? "" : mainTable.replace("`", "").replace("\"", "").strip();
        if (expected.isEmpty() || !Pattern.compile(
                "\\b(?:FROM|JOIN)\\s+(?:[A-Za-z0-9_]+\\.)?" + Pattern.quote(expected) + "\\b",
                Pattern.CASE_INSENSITIVE)
                .matcher(stripped).find()) {
            return failure("SQL 必须使用已确认主表 " + expected);
        }
        if (stripped.contains("{{") || stripped.contains("{%")) {
            return failure("SQL 模板仍包含未解析表达式");
        }
        return new ValidationResult(true, "安全校验通过");
    }

    private static String stripComments(String value) {
        return value.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--.*$", "");
    }

    private static ValidationResult failure(String message) {
        return new ValidationResult(false, message);
    }

    public record ValidationResult(boolean ok, String message) {}
}

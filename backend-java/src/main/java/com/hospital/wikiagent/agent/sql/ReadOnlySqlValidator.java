package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 对生成 SQL 执行只读、单语句、危险关键字和结果规模约束校验。
 *
 * <p>校验结果由确定性代码给出，不能依赖模型自我声明成功。任何医院、规则版本、统计周期或 SQL 链路不一致都必须阻止后续执行。</p>
 */
@Component
public class ReadOnlySqlValidator {
    private static final List<String> FORBIDDEN = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE", "REPLACE",
            "GRANT", "REVOKE", "EXEC", "EXECUTE", "LOAD", "MERGE");

    public ValidationResult validate(String sql, String mainTable) {
        return validate(sql, mainTable, true);
    }

    /**
     * 校验知识库中已通过对象契约检查的辅助 SQL。
     *
     * <p>源抽取、科室和患者 SQL 可能跨多张表，因此这里不重复要求单一主表；
     * 发布期的双库元数据与编译门禁负责确认实际对象。</p>
     */
    public ValidationResult validateReadOnly(String sql) {
        return validate(sql, "", false);
    }

    private ValidationResult validate(String sql, String mainTable, boolean requireMainTable) {
        String stripped = sql == null ? "" : sql.strip();
        String masked = maskCommentsAndLiterals(stripped);
        String upper = masked.strip().toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            return failure("只允许 SELECT 或 WITH...SELECT 查询");
        }
        String withoutTrailing = masked.replaceFirst(";+\\s*$", "");
        if (withoutTrailing.contains(";")) {
            return failure("禁止多语句 SQL");
        }
        for (String keyword : FORBIDDEN) {
            if (Pattern.compile("\\b" + keyword + "\\b", Pattern.CASE_INSENSITIVE).matcher(masked).find()) {
                return failure("禁止使用 " + keyword);
            }
        }
        if (Pattern.compile("\\b(?:SP_EXECUTESQL|OPENROWSET|OPENDATASOURCE)\\b",
                Pattern.CASE_INSENSITIVE).matcher(masked).find()) {
            return failure("禁止动态 SQL 或外部数据源调用");
        }
        if (Pattern.compile("(?:^|[^A-Za-z0-9_])#[A-Za-z_][A-Za-z0-9_]*")
                .matcher(masked).find()) {
            return failure("禁止使用临时表");
        }
        if (!containsControlledPeriodParameters(stripped)) {
            return failure("必须包含一组已登记的开始时间和结束时间参数");
        }
        String expected = mainTable == null ? "" : mainTable.replace("`", "").replace("\"", "").strip();
        if (requireMainTable && (expected.isEmpty() || !Pattern.compile(
                "\\b(?:FROM|JOIN)\\s+(?:[A-Za-z0-9_]+\\.)?" + Pattern.quote(expected) + "\\b",
                Pattern.CASE_INSENSITIVE)
                .matcher(masked).find())) {
            return failure("SQL 必须使用已确认主表 " + expected);
        }
        if (stripped.contains("{{") || stripped.contains("{%")
                || Pattern.compile("#(?:NAME\\?|EQUALS|ETC)|#\\{", Pattern.CASE_INSENSITIVE)
                        .matcher(stripped).find()) {
            return failure("SQL 模板仍包含未解析表达式");
        }
        return new ValidationResult(true, "安全校验通过");
    }

    /**
     * 原始指标 SQL 使用过三套时间参数命名。运行时不会改写知识库 SQL，而是在执行前
     * 将同一统计周期绑定到这些受控别名，因此安全校验也只接受这三组完整参数对。
     * 单独出现开始或结束参数、以及任意未知参数名仍然不能通过。
     */
    private static boolean containsControlledPeriodParameters(String sql) {
        return List.of(
                        new String[] {":start_time", ":end_time"},
                        new String[] {":startTime", ":endTime"},
                        new String[] {":marptBeginAt", ":marptEndAt"})
                .stream()
                .anyMatch(pair -> sql.contains(pair[0]) && sql.contains(pair[1]));
    }

    /**
     * 屏蔽注释、字符串和引用标识符后再扫描关键字，避免患者文本或注释中的
     * “delete/or”等普通单词造成误判，也避免字符串内部的分号被当成多语句。
     */
    private static String maskCommentsAndLiterals(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '-' && index + 1 < value.length() && value.charAt(index + 1) == '-') {
                while (index < value.length() && value.charAt(index) != '\n') {
                    result.append(' ');
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < value.length() && value.charAt(index + 1) == '*') {
                result.append("  ");
                index += 2;
                while (index < value.length()) {
                    if (value.charAt(index) == '*' && index + 1 < value.length()
                            && value.charAt(index + 1) == '/') {
                        result.append("  ");
                        index += 2;
                        break;
                    }
                    result.append(value.charAt(index) == '\n' ? '\n' : ' ');
                    index++;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '[') {
                char closing = current == '[' ? ']' : current;
                result.append(' ');
                index++;
                while (index < value.length()) {
                    char item = value.charAt(index);
                    result.append(item == '\n' ? '\n' : ' ');
                    index++;
                    if (item == closing) {
                        if (index < value.length() && value.charAt(index) == closing) {
                            result.append(' ');
                            index++;
                            continue;
                        }
                        break;
                    }
                }
                continue;
            }
            result.append(current);
            index++;
        }
        return result.toString();
    }

    private static ValidationResult failure(String message) {
        return new ValidationResult(false, message);
    }

    public record ValidationResult(boolean ok, String message) {}
}

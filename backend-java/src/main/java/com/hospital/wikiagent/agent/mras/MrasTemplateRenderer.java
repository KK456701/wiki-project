package com.hospital.wikiagent.agent.mras;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 解析知识库（knowledge-index）的 #ETC{} / #EQUALS{} 模板 SQL 语法。
 *
 * <p>职责边界：仅负责模板文本的条件裁剪、参数替换和方言修正；
 * 不执行 SQL、不访问数据库、不修改知识库原文件。
 * 与现有 SqlTemplateRenderer（Jinja 风格 {{ }}）完全独立，互不影响。</p>
 */
@Component
public class MrasTemplateRenderer {

    // #ETC{ ... } — 有对应参数则保留内容，否则删除整行
    private static final Pattern ETC_PATTERN = Pattern.compile(
            "^[ \\t]*#ETC\\{(.+?)}[ \\t]*\\r?\\n?", Pattern.MULTILINE | Pattern.DOTALL);

    // #EQUALS{:param; value; trueBranch} 或 #EQUALS{:param; value; trueBranch; falseBranch}
    private static final Pattern EQUALS_PATTERN = Pattern.compile(
            "#EQUALS\\{\\s*:(\\w+)\\s*;\\s*([^;]+?)\\s*;\\s*([^;]*?)\\s*(?:;\\s*([^}]*?)\\s*)?}");

    // 命名参数 :paramName（非 :: 开头，非行首 #EQUALS 里的）
    private static final Pattern NAMED_PARAM = Pattern.compile(":(\\w+)");

    /**
     * 渲染模板 SQL：条件裁剪 → 参数替换 → 方言修正。
     *
     * @param templateSql 原始模板 SQL（从实体页提取）
     * @param params      用户/系统提供的参数 Map（key 不含冒号前缀）
     * @return 渲染后的可执行 SQL
     */
    public String render(String templateSql, Map<String, Object> params) {
        if (templateSql == null || templateSql.isBlank()) {
            return "";
        }
        String result = templateSql;

        // 第一步：处理 #ETC{} 条件行
        result = processEtc(result, params);

        // 第二步：处理 #EQUALS{} 条件分支
        result = processEquals(result, params);

        // 第三步：替换命名参数为实际值
        result = replaceNamedParams(result, params);

        // 第四步：方言修正
        result = fixDialect(result);

        return result.strip();
    }

    /**
     * 仅解析模板语法（#ETC/#EQUALS）并修正方言，保留 :paramName 命名参数不替换。
     *
     * <p>输出可通过 ReadOnlySqlValidator 校验（仍含 :marptBeginAt 等标记），
     * 再交给 SqlParameterBinder 做防注入绑定。适用于需要走标准校验链路的场景。</p>
     *
     * @param templateSql 原始模板 SQL
     * @param params      用于 #ETC/#EQUALS 条件判定的参数（不用于值替换）
     * @return 模板已解析、方言已修正、但命名参数仍保留的 SQL
     */
    public String renderTemplate(String templateSql, Map<String, Object> params) {
        if (templateSql == null || templateSql.isBlank()) {
            return "";
        }
        String result = templateSql;
        result = processEtc(result, params);
        result = processEquals(result, params);
        result = fixDialect(result);
        return result.strip();
    }

    /**
     * #ETC{ content } — 如果 params 中包含 content 里引用的参数名，则保留 content；否则删除整行。
     * 参数名识别规则：content 中出现的 :paramName 或 (:paramName) 形式。
     */
    private String processEtc(String sql, Map<String, Object> params) {
        Matcher matcher = ETC_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String content = matcher.group(1).strip();
            // 从 content 中提取参数名（:xxx 形式）
            boolean hasParam = false;
            Matcher paramMatcher = NAMED_PARAM.matcher(content);
            while (paramMatcher.find()) {
                String paramName = paramMatcher.group(1);
                if (params.containsKey(paramName) && isNonEmpty(params.get(paramName))) {
                    hasParam = true;
                    break;
                }
            }
            if (hasParam) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(content + "\n"));
            } else {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * #EQUALS{:param; value; trueBranch} 或 #EQUALS{:param; value; trueBranch; falseBranch}
     * 如果 params.get(param) 的字符串值等于 value，则替换为 trueBranch；否则替换为 falseBranch（或空）。
     */
    private String processEquals(String sql, Map<String, Object> params) {
        Matcher matcher = EQUALS_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String expectedValue = matcher.group(2).strip();
            String trueBranch = matcher.group(3) == null ? "" : matcher.group(3).strip();
            String falseBranch = matcher.group(4) == null ? "" : matcher.group(4).strip();

            Object actual = params.get(paramName);
            String actualStr = actual == null ? "" : String.valueOf(actual).strip();

            String replacement = actualStr.equals(expectedValue) ? trueBranch : falseBranch;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 将 :paramName 替换为参数值（字符串值加单引号，数值不加）。
     * 跳过已在 #ETC/#EQUALS 处理中删除的内容。
     */
    private String replaceNamedParams(String sql, Map<String, Object> params) {
        Matcher matcher = NAMED_PARAM.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            Object value = params.get(paramName);
            if (value != null) {
                String replacement = formatValue(value);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            // 没有对应参数则保留原样（:paramName）
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * SQL Server 方言修正：
     * 1. ""别名"" → "别名"
     * 2. TABLE (NOLOCK) → TABLE WITH (NOLOCK)
     * 3. #{NOLOCK} 占位符 → WITH (NOLOCK)（知识库 V3 引入的新写法）
     * 4. 去除首尾多余的 "' 和 '"（实体页 SQL 块包裹符号）
     */
    private String fixDialect(String sql) {
        String result = sql;

        // 去除实体页 SQL 块首尾的 "'...'"  包裹
        result = result.strip();
        if (result.startsWith("\"'") && result.endsWith("'\"")) {
            result = result.substring(2, result.length() - 2);
        }

        // ""别名"" → "别名"
        result = result.replace("\"\"", "\"");

        // #{NOLOCK} 占位符（知识库 V3 新写法）→ (NOLOCK)，交给下方规则统一补 WITH；
        // 否则残留 #{ 会被只读校验判为未渲染模板
        result = result.replaceAll("(?i)#\\{\\s*NOLOCK\\s*\\}", "(NOLOCK)");

        // TABLE (NOLOCK) → TABLE WITH (NOLOCK)；先统一补 WITH，再把原本
        // 已带 WITH 被叠成的 WITH WITH 折回（lookbehind 在 \s* 回溯时拦不住）
        result = result.replaceAll("(?i)\\s*\\(NOLOCK\\)", " WITH (NOLOCK)");
        result = result.replaceAll("(?i)WITH\\s+WITH\\s+\\(NOLOCK\\)", "WITH (NOLOCK)");

        return result;
    }

    private static String formatValue(Object value) {
        if (value instanceof Number) {
            return value.toString();
        }
        String str = value.toString();
        // 已经是数字格式的不加引号
        if (str.matches("\\d+(\\.\\d+)?")) {
            return str;
        }
        // 字符串值加单引号，内部单引号转义
        return "'" + str.replace("'", "''") + "'";
    }

    private static boolean isNonEmpty(Object value) {
        if (value == null) {
            return false;
        }
        return !value.toString().isBlank();
    }
}

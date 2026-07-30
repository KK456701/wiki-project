package com.hospital.wikiagent.agent.mras;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 确定性明细 SQL 提取器：不依赖 LLM，从知识库实体页「目标表-科室统计」段的聚合 CTE
 * 逐字复刻分子/分母口径，机械转成两条明细查询。
 *
 * <p>核心做法是「保留差异」而非「解析差异」：概览/科室统计 SQL 的 FROM / JOIN / WHERE
 * 千变万化（主表别名可能是 event/t1/e、可能多表 JOIN、判定可能是 DATEDIFF 表达式或
 * JOIN 子查询列），本提取器一个字都不改，只做三件机械动作：
 * <ol>
 *   <li>把聚合列（COUNT(...) 等）整段丢弃，改成 {@code SELECT *}（列不影响行数）；</li>
 *   <li>删掉 {@code GROUP BY}（明细要行，不要按科室汇总）；</li>
 *   <li>分子明细在分母 WHERE 末尾追加 {@code AND (<判定条件>)}——判定条件即
 *       {@code COUNT(CASE WHEN <条件> THEN ...)} 里的 {@code <条件>}，原样复制。</li>
 * </ol>
 * FROM/JOIN/WHERE 及其中的模板标记（{@code #ETC{}}/{@code #EQUALS{}}/{@code #{NOLOCK}}/
 * {@code :marptBeginAt}）全部保留，交由 {@link MrasSqlExecutionService} 走标准渲染链路。</p>
 *
 * <p>分母若为 {@code COUNT(DISTINCT <键>)}，明细需按该键去重到一行（ROW_NUMBER 包裹），
 * 使行数与卡片的去重口径一致。</p>
 *
 * <p>无法机械转换的「异形」指标一律显式拒绝、不降级、不给对不上的数：
 * <ul>
 *   <li>找不到分子/分母聚合 CTE（如求和/中位数/时间类）；</li>
 *   <li>无 {@code COUNT(CASE WHEN)} 分子判定；</li>
 *   <li>存在两组及以上不同判定条件（比率的比率等复合指标）；</li>
 *   <li>聚合口径缺少 {@code GROUP BY}。</li>
 * </ul></p>
 *
 * <p>本类不执行 SQL、不访问网络、不修改知识库文件。</p>
 */
@Component
public class MrasDetailSqlExtractor {

    private static final Logger log = LoggerFactory.getLogger(MrasDetailSqlExtractor.class);

    /** 分子判定：COUNT(CASE WHEN <条件> THEN ...) 里的 <条件>（可含 DISTINCT）。 */
    private static final Pattern NUMERATOR_CASE = Pattern.compile(
            "COUNT\\s*\\(\\s*(?:DISTINCT\\s+)?CASE\\s+WHEN\\s+(.+?)\\s+THEN",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 分母去重键：COUNT(DISTINCT <列>)；不会误匹配 COUNT(DISTINCT CASE ...)（CASE 后接 WHEN 非右括号）。 */
    private static final Pattern DISTINCT_KEY = Pattern.compile(
            "COUNT\\s*\\(\\s*DISTINCT\\s+([A-Za-z_][\\w.]*)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /** CTE 头：{@code <名字> AS (}。 */
    private static final Pattern CTE_HEADER = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s+AS\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COUNT_ANY = Pattern.compile(
            "COUNT\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_CASE = Pattern.compile(
            "COUNT\\s*\\(\\s*(?:DISTINCT\\s+)?CASE", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_BY = Pattern.compile(
            "GROUP\\s+BY", Pattern.CASE_INSENSITIVE);

    private final EntityPageParser entityPageParser;

    public MrasDetailSqlExtractor(EntityPageParser entityPageParser) {
        this.entityPageParser = entityPageParser;
    }

    /**
     * 提取结果。
     *
     * @param supported        是否可确定性下钻
     * @param denominatorSql   分母明细 SQL（含命名参数/模板标记，未渲染；不支持时为 null）
     * @param numeratorSql     分子明细 SQL（同上）
     * @param unsupportedReason 不支持时的具体原因（支持时为 null）
     */
    public record DetailExtraction(
            boolean supported,
            String denominatorSql,
            String numeratorSql,
            String unsupportedReason) {

        static DetailExtraction unsupported(String reason) {
            return new DetailExtraction(false, null, null, reason);
        }

        static DetailExtraction of(String denominatorSql, String numeratorSql) {
            return new DetailExtraction(true, denominatorSql, numeratorSql, null);
        }
    }

    /**
     * 提取指定指标（可指定口径变体 profileId）的分母/分子明细 SQL。
     *
     * @param indicatorCode 指标编码（如 HXZD-003-001）
     * @param profileId     口径变体编码（如 HXZD-015-001_002，可为 null 表示主方案）
     */
    public DetailExtraction extract(String indicatorCode, String profileId) {
        EntityPageData entity = resolveEntity(indicatorCode, profileId);
        if (entity == null) {
            return DetailExtraction.unsupported("知识库中没有指标 " + indicatorCode + " 的实体页。");
        }

        // P0：只从「目标表-概览」口径提取（卡片分子/分母的真实来源），不回退科室统计 SQL。
        // 概览常用 DISTINCT 派生表去重，科室统计多为原始表直接聚合，二者去重语义可能不等价，
        // 回退会产生与卡片不一致的静默假阳性（如 HXZD-004-001：卡片 6/59，科室统计 30/155）。
        String source = entity.overviewSql();
        if (source == null || source.isBlank()) {
            return DetailExtraction.unsupported("该指标缺少「目标表-概览」口径 SQL，无法生成与卡片同源的明细。");
        }
        String sql = MrasSqlExecutionService.stripLeadingTrailingQuotes(source);

        String aggBody = findAggCteBody(sql);
        if (aggBody == null) {
            return DetailExtraction.unsupported(
                    "未定位到分子/分母聚合口径（多为求和、中位数、时间等非比率型指标）。");
        }

        List<String> conditions = collectNumeratorConditions(aggBody);
        if (conditions.isEmpty()) {
            return DetailExtraction.unsupported(
                    "该指标聚合口径无 COUNT(CASE WHEN) 分子判定（求和/时间/中位数等非比率指标）。");
        }
        if (conditions.size() >= 2) {
            return DetailExtraction.unsupported(
                    "该指标存在多组不同分子判定（比率的比率等复合指标），无单一分子/分母口径。");
        }
        String condition = conditions.get(0);

        String fromClause = sliceFromToGroupBy(aggBody);
        if (fromClause == null) {
            return DetailExtraction.unsupported("该指标聚合口径缺少 GROUP BY，无法机械转明细。");
        }

        // P0：分子需在概览 FROM/WHERE 之上追加分子判定。若顶层没有 WHERE（过滤条件位于派生表
        // 内部，如 HXZD-004-001 的 DISTINCT 去重子查询），直接追加 AND 会生成非法 SQL；此处显式
        // 拒绝、绝不静默返错，等 P0.5 语法感知改写（顶层无 WHERE 时改追加 WHERE）上线后再支持。
        if (topLevelKeyword(fromClause, "WHERE", true) < 0) {
            return DetailExtraction.unsupported(
                    "该指标概览口径的过滤条件位于派生表内部，暂不支持安全下钻（待语法感知改写上线）。");
        }

        String dedupKey = distinctKey(aggBody);
        String denominatorSql;
        String numeratorSql;
        if (dedupKey == null) {
            // 行级口径：分母 = COUNT(1)/COUNT(*)，明细直接取行。
            denominatorSql = "SELECT *\n" + fromClause;
            numeratorSql = "SELECT *\n" + fromClause + "\nAND (" + condition + ")";
        } else {
            // 去重口径：分母 = COUNT(DISTINCT <键>)，明细按键去重到一行。
            denominatorSql = wrapDistinct(fromClause, dedupKey, null);
            numeratorSql = wrapDistinct(fromClause, dedupKey, condition);
        }

        log.info("确定性明细提取成功 {}（profileId={}）：去重键={}，分子判定={}",
                indicatorCode, profileId, dedupKey == null ? "行级" : dedupKey, condition);
        return DetailExtraction.of(denominatorSql, numeratorSql);
    }

    private EntityPageData resolveEntity(String indicatorCode, String profileId) {
        EntityPageData entity = null;
        if (profileId != null && !profileId.isBlank() && !profileId.equals(indicatorCode)) {
            entity = entityPageParser.getEntity(profileId);
        }
        if (entity == null) {
            entity = entityPageParser.getEntity(indicatorCode);
        }
        return entity;
    }

    /**
     * 按 ROW_NUMBER 去重键包裹，保留 FROM/JOIN/WHERE 原文；仅取主表列（{@code <别名>.*}）
     * 避免派生表因 JOIN 子查询列与主表同名而报「列名重复」。
     *
     * @param fromClause 逐字 FROM..（GROUP BY 之前）片段
     * @param dedupKey   去重键（如 event.ENCOUNTER_ID）
     * @param extraCondition 分子追加判定；为 null 时是分母
     */
    private String wrapDistinct(String fromClause, String dedupKey, String extraCondition) {
        int dot = dedupKey.indexOf('.');
        String selectCols = dot > 0 ? dedupKey.substring(0, dot) + ".*" : "*";
        String inner = extraCondition == null
                ? fromClause
                : fromClause + "\nAND (" + extraCondition + ")";
        return "SELECT * FROM (\n"
                + "  SELECT " + selectCols + ", ROW_NUMBER() OVER (PARTITION BY " + dedupKey
                + " ORDER BY (SELECT NULL)) AS \"__detail_rn\"\n  "
                + inner + "\n) __detail_dedup\nWHERE __detail_dedup.\"__detail_rn\" = 1";
    }

    /**
     * 定位做分子/分母聚合的 CTE 体：含 {@code COUNT(} 且含 {@code GROUP BY}，优先含
     * {@code COUNT(CASE}。返回括号配平后的 CTE 内文；找不到返回 null。
     */
    private String findAggCteBody(String sql) {
        Matcher header = CTE_HEADER.matcher(sql);
        String firstCandidate = null;
        while (header.find()) {
            int open = header.end() - 1; // 指向 '('
            String body = balancedBody(sql, open);
            if (body == null) {
                continue;
            }
            boolean hasCount = COUNT_ANY.matcher(body).find();
            boolean hasGroupBy = GROUP_BY.matcher(body).find();
            if (!hasCount || !hasGroupBy) {
                continue;
            }
            if (COUNT_CASE.matcher(body).find()) {
                return body; // 优先返回含 COUNT(CASE 的
            }
            if (firstCandidate == null) {
                firstCandidate = body;
            }
        }
        return firstCandidate;
    }

    /**
     * 从 {@code openParenIdx} 处的左括号起，按括号配平截取内文（不含首尾括号）。
     */
    private String balancedBody(String sql, int openParenIdx) {
        int depth = 0;
        for (int i = openParenIdx; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(openParenIdx + 1, i);
                }
            }
        }
        return null;
    }

    /**
     * 收集 CTE 体内所有 {@code COUNT(CASE WHEN <条件> THEN)} 的判定条件，按规范化内容去重。
     * 「监测情况」行会重复出现同一条件，去重后算 1 种。
     */
    private List<String> collectNumeratorConditions(String cteBody) {
        List<String> conditions = new ArrayList<>();
        Matcher matcher = NUMERATOR_CASE.matcher(cteBody);
        while (matcher.find()) {
            String cond = matcher.group(1).replaceAll("\\s+", " ").strip();
            if (!cond.isEmpty() && !conditions.contains(cond)) {
                conditions.add(cond);
            }
        }
        return conditions;
    }

    /**
     * 分母去重键：{@code COUNT(DISTINCT <列>)} 的列名；行级口径（COUNT(1)/COUNT(*)）返回 null。
     */
    private String distinctKey(String cteBody) {
        Matcher matcher = DISTINCT_KEY.matcher(cteBody);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return null;
    }

    /**
     * 从 CTE 体切出「顶层 FROM」到「最后一个顶层 GROUP BY 之前」的逐字片段。
     * 顶层 = 括号深度 0，避免命中 JOIN 子查询内部的 FROM / GROUP BY。
     */
    private String sliceFromToGroupBy(String cteBody) {
        int fromIdx = topLevelKeyword(cteBody, "FROM", true);
        if (fromIdx < 0) {
            return null;
        }
        int groupByIdx = topLevelGroupBy(cteBody);
        if (groupByIdx < 0 || groupByIdx <= fromIdx) {
            return null;
        }
        return cteBody.substring(fromIdx, groupByIdx).strip();
    }

    /**
     * 查找括号深度 0 的关键字位置（词边界匹配）。
     *
     * @param first true 取首个，false 取最后一个
     */
    private int topLevelKeyword(String text, String keyword, boolean first) {
        String upper = text.toUpperCase(Locale.ROOT);
        int len = keyword.length();
        int depth = 0;
        int found = -1;
        for (int i = 0; i + len <= text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith(keyword, i)
                    && isBoundary(text, i, len)) {
                if (first) {
                    return i;
                }
                found = i;
            }
        }
        return found;
    }

    /** 查找最后一个括号深度 0 的 {@code GROUP BY}（GROUP 与 BY 间允许任意空白）。 */
    private int topLevelGroupBy(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        int depth = 0;
        int found = -1;
        for (int i = 0; i + 5 <= text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith("GROUP", i)
                    && (i == 0 || !isWordChar(text.charAt(i - 1)))) {
                int j = i + 5;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (upper.startsWith("BY", j) && isBoundary(text, j, 2)) {
                    found = i;
                }
            }
        }
        return found;
    }

    private boolean isBoundary(String text, int start, int len) {
        boolean before = start == 0 || !isWordChar(text.charAt(start - 1));
        int after = start + len;
        boolean afterOk = after >= text.length() || !isWordChar(text.charAt(after));
        return before && afterOk;
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}

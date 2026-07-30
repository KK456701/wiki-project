package com.hospital.wikiagent.agent.mras;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelInvoker.ModelCompletion;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.AgentModelUnavailableException;
import com.hospital.wikiagent.agent.model.ModelJsonExtractor;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator;
import com.hospital.wikiagent.agent.sql.ReadOnlySqlValidator.ValidationResult;

/**
 * 混合方案 C：用小模型从「概览 SQL」推导「分母明细 / 分子明细」两条患者明细查询。
 *
 * <p>职责边界：以知识库实体页的概览 SQL（编码了分子/分母逻辑）为依据，以患者明细 SQL
 * 作为列与 JOIN 的骨架参考，让小模型生成两条明细 SQL；生成结果必须通过
 * {@link ReadOnlySqlValidator} 只读校验（天然要求含 {@code :marptBeginAt/:marptEndAt}
 * 受控时间参数），校验失败带错误信息重试一次，仍失败返回 {@code null} 触发上游回退。
 * 合成结果不做任何缓存，每次调用都重新生成（用户明确要求，保证知识库修改后
 * 立即生效）；合成走小模型较慢（同步阻塞可达数十秒），调用方需自行承担耗时。</p>
 *
 * <p>本类不执行 SQL、不修改知识库文件；执行交由 {@link MrasSqlExecutionService}
 * 走标准链路（参数绑定 {@code SqlParameterBinder} + DBHub MCP）。</p>
 */
@Component
public class MrasDetailSqlSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(MrasDetailSqlSynthesizer.class);
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

    /** 概览 SQL 中分子列的固定形态：COUNT/SUM(CASE WHEN <判定表达式> THEN ...)，取第一处即分子。 */
    private static final Pattern NUMERATOR_CASE = Pattern.compile(
            "(?:COUNT|SUM)\\s*\\(\\s*CASE\\s+WHEN\\s+(.+?)\\s+THEN",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 列表达式中的表别名引用（如 event.、emp1.），用于骨架列白名单判定。 */
    private static final Pattern ALIAS_REF = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.");

    private final AgentModelInvoker invoker;
    private final AgentModelRegistry registry;
    private final EntityPageParser entityPageParser;
    private final ReadOnlySqlValidator sqlValidator;
    private final ObjectMapper objectMapper;

    public MrasDetailSqlSynthesizer(
            AgentModelInvoker invoker,
            AgentModelRegistry registry,
            EntityPageParser entityPageParser,
            ReadOnlySqlValidator sqlValidator,
            ObjectMapper objectMapper) {
        this.invoker = invoker;
        this.registry = registry;
        this.entityPageParser = entityPageParser;
        this.sqlValidator = sqlValidator;
        this.objectMapper = objectMapper;
    }

    /** 合成结果：分母明细 SQL 与分子明细 SQL（均含命名时间参数，已通过只读校验）。 */
    public record DetailSqlPair(String denominatorSql, String numeratorSql) {
    }

    /**
     * 合成指定指标的分母/分子明细 SQL；每次调用都重新生成（不缓存），合成失败返回 {@code null}（上游回退）。
     */
    public DetailSqlPair synthesize(String indicatorCode) {
        if (indicatorCode == null || indicatorCode.isBlank()) {
            return null;
        }
        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        if (entity == null || !entity.hasOverviewSql()) {
            return null;
        }

        Attempt first = attempt(entity, null);
        if (first.pair() != null) {
            log.info("知识库明细 SQL 合成成功 {}\n分母明细 SQL: {}\n分子明细 SQL: {}",
                    indicatorCode, first.pair().denominatorSql(), first.pair().numeratorSql());
            return first.pair();
        }
        // 模型不可用（连不上/超时）时重试只会再白等一次超时，直接回退；只对校验失败带错误信息重试一次
        if (!first.retryable()) {
            log.warn("知识库明细 SQL 合成失败（模型不可用，不重试）{}: {}", indicatorCode, first.error());
            return null;
        }
        Attempt second = attempt(entity, first.error());
        if (second.pair() != null) {
            log.info("知识库明细 SQL 重试合成成功 {}\n分母明细 SQL: {}\n分子明细 SQL: {}",
                    indicatorCode, second.pair().denominatorSql(), second.pair().numeratorSql());
            return second.pair();
        }
        log.warn("知识库明细 SQL 合成失败 {}: {}", indicatorCode, second.error());
        return null;
    }

    /** 单次生成尝试：成功返回 pair，失败返回错误信息（供重试反馈）。 */
    private Attempt attempt(EntityPageData entity, String previousError) {
        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(entity, previousError);
            ModelCompletion completion = invoker.complete(
                    registry.defaultModelId(), systemPrompt, userPrompt, MODEL_TIMEOUT);
            String json = ModelJsonExtractor.firstObject(completion.content());
            JsonNode node = objectMapper.readTree(json);
            // 模型输出的 JSON 值已是干净 SQL（非知识库那种引号包裹块），不能剥首尾引号，
            // 否则会误删 SQL 字符串字面量的闭合引号（如 VERSION = 'V2.0'）导致语法错误。
            String denominator = text(node.get("denominator_sql")).strip();
            String numerator = text(node.get("numerator_sql")).strip();
            if (denominator.isBlank() || numerator.isBlank()) {
                return new Attempt(null, "模型未返回完整的 denominator_sql / numerator_sql。", true);
            }
            ValidationResult denominatorValidation = sqlValidator.validateReadOnly(denominator);
            ValidationResult numeratorValidation = sqlValidator.validateReadOnly(numerator);
            if (!denominatorValidation.ok() || !numeratorValidation.ok()) {
                return new Attempt(null,
                        "分母明细 SQL 校验：" + denominatorValidation.message()
                                + "；分子明细 SQL 校验：" + numeratorValidation.message(), true);
            }
            return new Attempt(new DetailSqlPair(denominator, numerator), null, false);
        } catch (AgentModelUnavailableException exception) {
            // 模型连不上或超时：重试大概率同样失败，标记为不可重试避免双倍阻塞
            return new Attempt(null, exception.getMessage(), false);
        } catch (Exception exception) {
            return new Attempt(null, exception.getMessage(), true);
        }
    }

    private static String buildSystemPrompt() {
        return """
                你是医疗指标 SQL Server 查询专家。任务：根据给定的「概览 SQL」生成两条患者明细查询——「分母明细」和「分子明细」。

                硬性要求（必须全部满足，否则视为无效）：
                1. 只能生成 SELECT 查询，禁止任何 INSERT/UPDATE/DELETE/EXEC 等写操作、存储过程或动态 SQL 调用。
                2. 时间过滤必须使用命名参数 :marptBeginAt 和 :marptEndAt，禁止写死具体日期字面量。
                3. 所有表引用保留 WITH (NOLOCK)。
                4. 分母明细必须严格复刻概览 SQL 中「分母」（如 COUNT(1)）所统计的人群：使用与概览 SQL 相同的主表（沿用别名 event）与相同的 WHERE 时间过滤（必须含 :marptBeginAt/:marptEndAt，可保留 IS_DEL、VERSION 等基础有效性过滤）。分母明细绝对不要 JOIN 任何其他业务表，不要追加任何与分子判定相关的过滤条件。
                5. 分子明细的生成方式必须是机械的：先完整复制你生成的分母明细 SQL，然后仅在 WHERE 子句末尾追加一个 AND 条件——该条件就是用户消息中【分子判定表达式】给出的内容（已从概览 SQL 的 COUNT(CASE WHEN <判定表达式> THEN 1 ELSE NULL END) 中机械提取），必须原样照抄、一字不改。例如分母 WHERE 结尾是 ... AND event.VERSION = 'V2.0'，分子就是 ... AND event.VERSION = 'V2.0' AND (<判定表达式>)。严禁在分子明细中使用 EXISTS、JOIN 或任何子查询，严禁引用主表以外的任何表，分子过滤条件只能使用主表 event 上的列。
                6. 输出列只允许来自主表（别名 event），从下方「患者明细输出列参考」中选取属于 event 的列（如患者标识、住院号、患者姓名、科室、入出区时间等）；禁止引用 team.、t1.、o1.、o2.、inp.、emp1. 等任何其他表别名的列，以免出现未绑定列错误。列别名使用双引号包裹并沿用骨架风格；骨架仅供选取输出列与别名参考，其 JOIN 与 WHERE 过滤不要照抄。
                7. 分母明细与分子明细必须是两条不同的 SQL：分母不含分子判定条件，分子含分子判定条件，二者返回行数应不同。
                8. 列别名使用双引号包裹（如 "患者姓名"），与骨架保持一致。
                9. 输出 SQL 中严禁出现任何模板标记，包括但不限于 #ETC{...}、#EQUALS{...}、#NAME?、#{...}、{{...}}、{%...%}；下方骨架与概览 SQL 里的这类标记只是参考，必须删除，绝不能照抄到输出里。
                10. 只输出一个 JSON 对象，格式严格为：{"denominator_sql":"...","numerator_sql":"..."}，不要任何解释文字、不要 Markdown 代码块。
                """;
    }

    private static String buildUserPrompt(EntityPageData entity, String previousError) {
        StringBuilder sb = new StringBuilder();
        sb.append("指标编码：").append(entity.code()).append('\n');
        sb.append("指标名称：").append(entity.name()).append("\n\n");
        sb.append("【分子/分母定义】\n");
        if (entity.formula() != null && !entity.formula().isBlank()) {
            sb.append(entity.formula().strip()).append('\n');
        }
        if (entity.caliber() != null && !entity.caliber().isBlank()) {
            sb.append(entity.caliber().strip()).append('\n');
        }
        String overviewSql = stripTemplateMarkers(entity.overviewSql());
        sb.append("\n【概览 SQL】\n").append(overviewSql).append('\n');
        String numeratorCondition = extractNumeratorCondition(overviewSql);
        if (!numeratorCondition.isBlank()) {
            sb.append("\n【分子判定表达式（已从概览 SQL 机械提取；分子明细必须在分母 WHERE 末尾原样追加 AND (该表达式)）】\n")
                    .append(numeratorCondition).append('\n');
        }
        String skeletonColumns = skeletonColumnList(stripTemplateMarkers(entity.patientDetailSql()));
        if (!skeletonColumns.isBlank()) {
            sb.append("\n【患者明细输出列参考（仅供选取输出列与别名；禁止照抄患者明细 SQL 的 JOIN、WHERE、转科表 INPAT_TRANSFER 逻辑）】\n")
                    .append(skeletonColumns).append('\n');
        }
        if (previousError != null && !previousError.isBlank()) {
            sb.append("\n【上次生成的 SQL 未通过校验，请修正后重新生成】\n")
                    .append(previousError.strip()).append('\n');
        }
        sb.append("\n请生成 JSON。\n");
        return sb.toString();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    /**
     * 从概览 SQL 中机械提取分子判定表达式：取第一处 {@code COUNT/SUM(CASE WHEN <表达式> THEN}
     * 的 WHEN 条件（概览 SQL 的分子列固定是该形态，达标率等后续 CASE 不含 COUNT/SUM 前缀）。
     * 提取结果直接注入提示词，替代早期硬编码的单指标示例，保证任意指标都拿到自己的判定条件。
     */
    static String extractNumeratorCondition(String overviewSql) {
        if (overviewSql == null || overviewSql.isBlank()) {
            return "";
        }
        Matcher matcher = NUMERATOR_CASE.matcher(overviewSql);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replaceAll("\\s+", " ").strip();
    }

    /**
     * 剥离知识库 SQL 中的条件模板标记 {@code #ETC{...}} 与 {@code #EQUALS{...}}（含嵌套花括号），
     * 并去除首尾引号。骨架与概览 SQL 仅供小模型参考列与 JOIN，这些标记若照抄进生成 SQL
     * 会被 {@link ReadOnlySqlValidator} 以「未解析表达式」拦截，因此在展示给模型前先清洗。
     */
    private static String stripTemplateMarkers(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String cleaned = MrasSqlExecutionService.stripLeadingTrailingQuotes(sql);
        StringBuilder sb = new StringBuilder(cleaned.length());
        int index = 0;
        while (index < cleaned.length()) {
            if (cleaned.startsWith("#ETC{", index) || cleaned.startsWith("#EQUALS{", index)) {
                int braceStart = cleaned.indexOf('{', index);
                int depth = 0;
                int cursor = braceStart;
                while (cursor < cleaned.length()) {
                    char ch = cleaned.charAt(cursor);
                    if (ch == '{') {
                        depth++;
                    } else if (ch == '}') {
                        depth--;
                        if (depth == 0) {
                            cursor++;
                            break;
                        }
                    }
                    cursor++;
                }
                sb.append(' ');
                index = cursor;
                continue;
            }
            sb.append(cleaned.charAt(index));
            index++;
        }
        return sb.toString().strip();
    }

    /**
     * 仅提取骨架 SELECT 列清单中主表（别名 event）的列并以逗号换行拼接，
     * 采用白名单判定：列表达式里出现的所有「别名.」引用必须都是 event，
     * 引用任何其他别名（team/t1/o1/o2/inp/emp1 等，随知识库骨架变化不可枚举）
     * 的列一律丢弃，避免小模型照抄未 JOIN 的维表列导致「未绑定标识符」错误。
     * 只返回「列清单」本身，不含 FROM/JOIN/WHERE，从根本上避免小模型照抄骨架里
     * 依赖转科表 INPAT_TRANSFER 的 JOIN 与 WHERE 过滤导致生成错误的 EXISTS/JOIN
     * 逻辑；骨架的唯一用途是参考输出列名与中文别名。
     */
    static String skeletonColumnList(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        int selectIdx = upper.indexOf("SELECT");
        if (selectIdx < 0) {
            return "";
        }
        int listStart = selectIdx + "SELECT".length();
        int fromIdx = indexOfTopLevelFrom(sql, listStart);
        if (fromIdx < 0) {
            return "";
        }
        String columnList = sql.substring(listStart, fromIdx);
        List<String> kept = new ArrayList<>();
        for (String item : splitTopLevelCommas(columnList)) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!referencesOnlyEventAlias(trimmed)) {
                continue;
            }
            kept.add(trimmed);
        }
        return String.join(",\n", kept);
    }

    /** 白名单判定：表达式中出现的每个「别名.」引用都必须是主表 event，否则不采纳该列。 */
    private static boolean referencesOnlyEventAlias(String expression) {
        Matcher matcher = ALIAS_REF.matcher(expression);
        while (matcher.find()) {
            if (!"event".equalsIgnoreCase(matcher.group(1))) {
                return false;
            }
        }
        return true;
    }

    /** 从 start 起查找括号深度为 0 的 FROM 关键字位置（区分大小写边界）。 */
    private static int indexOfTopLevelFrom(String sql, int start) {
        String upper = sql.toUpperCase(Locale.ROOT);
        int depth = 0;
        for (int i = start; i + 4 <= sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith("FROM", i)) {
                boolean boundaryBefore = i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1));
                boolean boundaryAfter = i + 4 >= sql.length()
                        || !Character.isLetterOrDigit(sql.charAt(i + 4));
                if (boundaryBefore && boundaryAfter) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 按括号深度为 0 的逗号拆分 SELECT 列清单。 */
    private static List<String> splitTopLevelCommas(String value) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    private record Attempt(DetailSqlPair pair, String error, boolean retryable) {
    }
}
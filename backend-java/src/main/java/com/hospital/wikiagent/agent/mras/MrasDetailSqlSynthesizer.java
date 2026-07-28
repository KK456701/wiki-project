package com.hospital.wikiagent.agent.mras;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelInvoker.ModelCompletion;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
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
 * 每次调用均重新生成，不做缓存。</p>
 *
 * <p>本类不执行 SQL、不修改知识库文件；执行交由 {@link MrasSqlExecutionService}
 * 走标准链路（参数绑定 {@code SqlParameterBinder} + DBHub MCP）。</p>
 */
@Component
public class MrasDetailSqlSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(MrasDetailSqlSynthesizer.class);
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);

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
     * 合成指定指标的分母/分子明细 SQL；每次重新生成，合成失败返回 {@code null}（上游回退）。
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
            log.info("领导知识库明细 SQL 合成成功 {}\n分母明细 SQL: {}\n分子明细 SQL: {}",
                    indicatorCode, first.pair().denominatorSql(), first.pair().numeratorSql());
            return first.pair();
        }
        // 带错误信息重试一次
        Attempt second = attempt(entity, first.error());
        if (second.pair() != null) {
            log.info("领导知识库明细 SQL 重试合成成功 {}\n分母明细 SQL: {}\n分子明细 SQL: {}",
                    indicatorCode, second.pair().denominatorSql(), second.pair().numeratorSql());
            return second.pair();
        }
        log.warn("领导知识库明细 SQL 合成失败 {}: {}", indicatorCode, second.error());
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
                return new Attempt(null, "模型未返回完整的 denominator_sql / numerator_sql。");
            }
            ValidationResult denominatorValidation = sqlValidator.validateReadOnly(denominator);
            ValidationResult numeratorValidation = sqlValidator.validateReadOnly(numerator);
            if (!denominatorValidation.ok() || !numeratorValidation.ok()) {
                return new Attempt(null,
                        "分母明细 SQL 校验：" + denominatorValidation.message()
                                + "；分子明细 SQL 校验：" + numeratorValidation.message());
            }
            return new Attempt(new DetailSqlPair(denominator, numerator), null);
        } catch (Exception exception) {
            return new Attempt(null, exception.getMessage());
        }
    }

    private static String buildSystemPrompt() {
        return """
                你是医疗指标 SQL Server 查询专家。任务：根据给定的「概览 SQL」生成两条患者明细查询——「分母明细」和「分子明细」。

                硬性要求（必须全部满足，否则视为无效）：
                1. 只能生成 SELECT 查询，禁止任何 INSERT/UPDATE/DELETE/EXEC 等写操作、存储过程或动态 SQL 调用。
                2. 时间过滤必须使用命名参数 :marptBeginAt 和 :marptEndAt，禁止写死具体日期字面量。
                3. 所有表引用保留 WITH (NOLOCK)。
                4. 分母明细必须严格复刻概览 SQL 中「分母」（如 COUNT(1)）所统计的人群：使用与概览 SQL 相同的主表与 WHERE 时间过滤（例如 FROM MRAS_BUSINESS_FIRSTVISIT event WITH (NOLOCK) WHERE event.ADMITTED_TO_WARD_AT BETWEEN :marptBeginAt AND :marptEndAt，可保留 IS_DEL、VERSION 等基础有效性过滤）。分母明细绝对不要 JOIN 转科表 INPAT_TRANSFER，不要加 t1.INPAT_TRANSFER_ID IS NOT NULL、转科类型等任何与分子相关的过滤。
                5. 分子明细的生成方式必须是机械的：先完整复制你生成的分母明细 SQL，然后仅在 WHERE 子句末尾追加一个 AND 条件——该条件就是把概览 SQL 中分子 COUNT(CASE WHEN <判定表达式> THEN 1 ELSE NULL END) 里的 <判定表达式> 原样照抄进来（本指标即 event.TRANSFER_WITHIN_TWO_DAY = '98175'）。例如分母 WHERE 结尾是 ... AND event.VERSION = 'V2.0'，分子就是 ... AND event.VERSION = 'V2.0' AND (event.TRANSFER_WITHIN_TWO_DAY = '98175')。严禁在分子明细中使用 EXISTS、JOIN 或任何子查询，严禁引用 INPAT_TRANSFER 等其他表，分子过滤条件只能使用主表 event 上的列。
                6. 输出列只允许来自主表（别名 event），例如 event.ENCOUNTER_ID、event.IMRN（住院号）、event.PERSON_NAME（患者姓名）、event.CURRENT_DEPT_ID/CURRENT_DEPT_NAME（科室）、event.ADMITTED_TO_WARD_AT（入区时间）、event.WARD_DISCHARGED_AT（出区时间）等；禁止引用 team.、t1.、o1.、o2. 等其他表别名的列，以免出现未绑定列错误。列别名使用双引号包裹并沿用骨架风格；骨架仅供选取输出列与别名参考，其 JOIN 与 WHERE 过滤不要照抄。
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
        sb.append("\n【概览 SQL】\n").append(stripTemplateMarkers(entity.overviewSql())).append('\n');
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
     * 丢弃引用其他表别名（team./t1./o1./o2./inp. 等）的列。只返回「列清单」本身，
     * 不含 FROM/JOIN/WHERE，从根本上避免小模型照抄骨架里依赖转科表 INPAT_TRANSFER
     * 的 JOIN 与 WHERE 过滤（如 t1.INPAT_TRANSFER_TYPE_CODE 之类）导致生成错误的
     * EXISTS/JOIN 逻辑；骨架的唯一用途是参考输出列名与中文别名。
     */
    private static String skeletonColumnList(String sql) {
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
            if (trimmed.toLowerCase(Locale.ROOT).matches(".*\\b(?:team|t1|o1|o2|inp)\\..*")) {
                continue;
            }
            kept.add(trimmed);
        }
        return String.join(",\n", kept);
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

    private record Attempt(DetailSqlPair pair, String error) {
    }
}
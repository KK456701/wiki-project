package com.hospital.wikiagent.agent.mras;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * 概览 SQL 对每个指标是静态的，合成结果按指标编码缓存，避免重复调用模型。</p>
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

    private final Map<String, DetailSqlPair> cache = new ConcurrentHashMap<>();

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
     * 合成指定指标的分母/分子明细 SQL；命中缓存直接返回，合成失败返回 {@code null}（上游回退）。
     */
    public DetailSqlPair synthesize(String indicatorCode) {
        if (indicatorCode == null || indicatorCode.isBlank()) {
            return null;
        }
        DetailSqlPair cached = cache.get(indicatorCode);
        if (cached != null) {
            return cached;
        }
        EntityPageData entity = entityPageParser.getEntity(indicatorCode);
        if (entity == null || !entity.hasOverviewSql()) {
            return null;
        }

        Attempt first = attempt(entity, null);
        if (first.pair() != null) {
            cache.put(indicatorCode, first.pair());
            return first.pair();
        }
        // 带错误信息重试一次
        Attempt second = attempt(entity, first.error());
        if (second.pair() != null) {
            cache.put(indicatorCode, second.pair());
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
            String denominator = MrasSqlExecutionService.stripLeadingTrailingQuotes(
                    text(node.get("denominator_sql")));
            String numerator = MrasSqlExecutionService.stripLeadingTrailingQuotes(
                    text(node.get("numerator_sql")));
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
                4. 分母明细 = 统计区间内的基础人群（全部入院患者），不加分子判定条件。
                5. 分子明细 = 基础人群 + 概览 SQL 中用于统计分子的判定条件（例如 CASE WHEN 里的条件）。
                6. 尽量复用「患者明细 SQL 骨架」中的列别名与 JOIN 写法；分母明细可不依赖转科表，相关列可省略。
                7. 列别名使用双引号包裹（如 "患者姓名"），与骨架保持一致。
                8. 禁止使用 # 开头的临时表或 #ETC/#EQUALS 等模板表达式。
                9. 只输出一个 JSON 对象，格式严格为：{"denominator_sql":"...","numerator_sql":"..."}，不要任何解释文字、不要 Markdown 代码块。
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
        sb.append("\n【概览 SQL】\n").append(entity.overviewSql().strip()).append('\n');
        if (entity.patientDetailSql() != null && !entity.patientDetailSql().isBlank()) {
            sb.append("\n【患者明细 SQL 骨架（参考列与 JOIN）】\n")
                    .append(entity.patientDetailSql().strip()).append('\n');
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

    private record Attempt(DetailSqlPair pair, String error) {
    }
}

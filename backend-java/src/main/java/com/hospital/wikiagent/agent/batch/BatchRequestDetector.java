package com.hospital.wikiagent.agent.batch;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.batch.BatchRequestSpec.Target;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
import com.hospital.wikiagent.rules.WikiRuleKnowledgeSource;

/**
 * 用确定性正则识别“计算全部指标结果”这类批量请求。
 *
 * <p>批量场景的计划完全确定（意图固定为试运行、指标可枚举、时间来自父请求），因此识别
 * 不依赖小模型判断，避免 7B 模型在批量意图识别上不稳定。只有同时命中“全部范围词”“结果词”
 * 且不含“定义/口径”等排除词时才判定为批量试运行；否则交回原有单指标/复合路径。</p>
 */
@Component
public class BatchRequestDetector {
    private final WikiRuleKnowledgeSource rules;

    public BatchRequestDetector() {
        this.rules = null;
    }

    @Autowired
    public BatchRequestDetector(WikiRuleKnowledgeSource rules) {
        this.rules = rules;
    }

    /** 表示“全部指标”的范围词，要求与“指标”紧邻（可插入核心/重点）。 */
    private static final Pattern SCOPE_ALL = Pattern.compile(
            "(所有|全部|全院)(核心|重点)?指标"
                    + "|全指标|每一项指标|每个指标|各项指标"
                    + "|逐一(计算|算)|逐个(计算|算)");

    /** 表示用户想要“计算结果”而非口径解释的动词/名词。
     * 句首或标点后的光杆“算”（如“算去年…比例”）同样是计算意图，排除“算了”；
     * “怎么算/如何计算”由 WANTS_DEFINITION 先行拦截，不会误入。 */
    private static final Pattern WANTS_RESULT = Pattern.compile(
            "计算|结果|数值|试运行|得分|监测|达标|算一遍|算一下|都算"
                    + "|帮我算|给我算|重算|再算"
                    + "|(?:^|[，。？！、；：,;])算(?!了)");

    /** 命中即视为口径/定义类问题，不进入批量试运行。 */
    private static final Pattern WANTS_DEFINITION = Pattern.compile(
            "定义|口径|公式|是什么|什么意思|怎么算|如何计算|解释|含义");
    private static final Pattern TIME_CHANGE = Pattern.compile(
            "修改时间|时间改成|统计时间|统计区间|时间范围|到现在|至今|本月|这个月|当月|"
                    + "上月|上个月|今年|去年|"
                    + "[一二三四五六七八九十]{1,3}月份?|"
                    + "\\d{2,4}年|\\d{4}-\\d{1,2}-\\d{1,2}");

    /** 命中即说明句子里带有具体指标语义（如“入院内转科的比例”），不是纯时间修改，必须交给语义召回。 */
    private static final Pattern INDICATOR_CONTENT = Pattern.compile(
            "率|比例|会诊|转科|查房|患者|住院|手术|抢救|死亡|感染|输血");

    /**
     * 判断一条用户问题是否应进入批量指标计算路径。
     */
    public BatchRequestSpec detect(String query) {
        return detect(query, null, List.of());
    }

    public BatchRequestSpec detect(
            String query, QueryScopeState previous, String hospitalId) {
        List<Map<String, String>> indicators = rules == null
                ? List.of() : rules.activeIndicatorNames(hospitalId, 500);
        return detect(query, previous, indicators);
    }

    /**
     * 结合结构化会话范围识别批量续问。当前消息的明确范围优先于历史范围；只有纯时间
     * 修改或“全部指标”这类范围续答可以继承上一轮试运行操作。
     */
    public BatchRequestSpec detect(
            String query,
            QueryScopeState previous,
            List<Map<String, String>> activeIndicators) {
        if (query == null || query.isBlank()) {
            return BatchRequestSpec.notBatch();
        }
        String normalized = normalize(query);
        if (WANTS_DEFINITION.matcher(normalized).find()) {
            return BatchRequestSpec.notBatch();
        }
        boolean scopeAll = SCOPE_ALL.matcher(normalized).find();
        boolean wantsResult = WANTS_RESULT.matcher(normalized).find();
        boolean continuesTrial = previous != null
                && previous.valid()
                && "indicator_trial_run".equals(previous.operation());
        String timeText = timeText(query, previous);
        List<Target> explicitTargets = explicitTargets(query, activeIndicators);

        // 明确点名的指标优先于“全部指标”措辞：澄清续答或原句里同时出现
        // 指标名和“全部指标”时，按用户点名的指标计算，不能退化成全量。
        if (!explicitTargets.isEmpty() && (wantsResult || continuesTrial)) {
            return BatchRequestSpec.selected(query, timeText, explicitTargets);
        }
        if (scopeAll && (wantsResult || continuesTrial)) {
            return BatchRequestSpec.allActive(query, timeText);
        }
        if (continuesTrial && isTimeOnlyChange(query) && explicitTargets.isEmpty()) {
            if ("ALL".equals(previous.targetMode())) {
                return BatchRequestSpec.allActive(query, query);
            }
            if (("SINGLE".equals(previous.targetMode())
                        || "SUBSET".equals(previous.targetMode()))
                    && !previous.targets().isEmpty()) {
                List<Target> remembered = previous.targets().stream()
                        .map(value -> new Target(value.ruleId(), value.ruleName()))
                        .toList();
                return BatchRequestSpec.selected(query, query, remembered);
            }
        }
        if (!normalized.contains("指标")) {
            return BatchRequestSpec.notBatch();
        }
        if (scopeAll && wantsResult) {
            return BatchRequestSpec.allActive(query);
        }
        return BatchRequestSpec.notBatch();
    }

    public boolean isTrialRunRequest(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = normalize(query);
        return !WANTS_DEFINITION.matcher(normalized).find()
                && WANTS_RESULT.matcher(normalized).find();
    }

    public boolean isBareAllScope(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = normalize(query)
                .replaceFirst("^我选择的指标是[:：]?", "");
        return normalized.matches("(全部|所有|全院|35项)(核心|重点)?指标");
    }

    public boolean isAllScope(String query) {
        return query != null && SCOPE_ALL.matcher(normalize(query)).find();
    }

    /**
     * 返回请求中明确点名的已审批 Profile。完整 profile_id、正式名称，以及
     * “默认/当前口径”都由服务端知识契约解析，不能把任意用户文本当成 Profile。
     */
    public Target explicitProfileTarget(
            String query, String ruleId, String ruleName, String hospitalId) {
        if (rules == null || query == null || query.isBlank()
                || ruleId == null || ruleId.isBlank()) {
            return null;
        }
        String normalized = normalize(query);
        List<Map<String, Object>> profiles = rules.caliberProfiles(ruleId, hospitalId);
        for (Map<String, Object> profile : profiles) {
            String profileId = text(profile.get("profile_id"));
            String profileLabel = first(
                    text(profile.get("label")),
                    text(profile.get("profile_name")),
                    profileId);
            if ((!profileId.isBlank()
                        && normalized.toLowerCase().contains(
                                normalize(profileId).toLowerCase()))
                    || (!profileLabel.isBlank()
                        && normalized.contains(normalize(profileLabel)))) {
                return new Target(ruleId, ruleName, profileId, profileLabel);
            }
        }
        if (normalized.contains("默认口径") || normalized.contains("当前口径")) {
            Map<String, Object> effective = rules.effectiveRule(ruleId, hospitalId);
            String profileId = text(effective.get("profile_id"));
            if (!profileId.isBlank()) {
                return new Target(
                        ruleId,
                        ruleName,
                        profileId,
                        first(
                                text(effective.get("profile_name")),
                                text(effective.get("label")),
                                profileId));
            }
        }
        return null;
    }

    /** 未明确 Profile 时，只有存在多个已审批 Profile 才需要展开为 Profile 批次。 */
    public boolean requiresProfileExpansion(
            String query, String ruleId, String ruleName, String hospitalId) {
        if (rules == null || ruleId == null || ruleId.isBlank()) {
            return false;
        }
        return rules.caliberProfiles(ruleId, hospitalId).size() > 1
                && explicitProfileTarget(
                        query, ruleId, ruleName, hospitalId) == null;
    }

    /** 返回当前医院全部活跃指标，供非计算类的 35 项确定性展开使用。 */
    public List<Target> allActiveTargets(String hospitalId) {
        if (rules == null) {
            return List.of();
        }
        return rules.activeIndicatorNames(hospitalId, 500).stream()
                .map(value -> new Target(value.get("rule_id"), value.get("rule_name")))
                .filter(value -> value.ruleId() != null && !value.ruleId().isBlank()
                        && value.ruleName() != null && !value.ruleName().isBlank())
                .limit(35)
                .toList();
    }

    public boolean isTimeOnlyChange(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = normalize(query);
        // 含指标语义内容（如“算去年患者入院内转科的比例”）的句子不是纯时间修改，
        // 不能在这里提前拦截，必须放行给语义召回 + LLM 兜底识别指标。
        return normalized.length() <= 80
                && TIME_CHANGE.matcher(normalized).find()
                && !WANTS_DEFINITION.matcher(normalized).find()
                && !WANTS_RESULT.matcher(normalized).find()
                && !INDICATOR_CONTENT.matcher(normalized).find();
    }

    public String timeTextFor(String query, QueryScopeState previous) {
        return timeText(query, previous);
    }

    public List<Target> explicitTargets(
            String query, List<Map<String, String>> activeIndicators) {
        if (query == null || query.isBlank()
                || activeIndicators == null || activeIndicators.isEmpty()) {
            return List.of();
        }
        return activeIndicators.stream()
                .map(value -> new Target(value.get("rule_id"), value.get("rule_name")))
                .filter(value -> !value.ruleId().isBlank() && !value.ruleName().isBlank())
                .filter(value -> query.contains(value.ruleName())
                        || query.toUpperCase().contains(value.ruleId().toUpperCase()))
                .distinct()
                .sorted(Comparator.comparingInt(value -> position(query, value)))
                .limit(35)
                .toList();
    }

    private static int position(String query, Target target) {
        int byName = query.indexOf(target.ruleName());
        int byId = query.toUpperCase().indexOf(target.ruleId().toUpperCase());
        if (byName < 0) return byId < 0 ? Integer.MAX_VALUE : byId;
        if (byId < 0) return byName;
        return Math.min(byName, byId);
    }

    private static String timeText(String query, QueryScopeState previous) {
        String normalized = normalize(query);
        if ((normalized.contains("上次统计时间")
                    || normalized.contains("上次时间")
                    || normalized.contains("之前的统计时间"))
                && previous != null
                && previous.statStart() != null
                && previous.statEnd() != null) {
            return previous.statStart().replace(' ', 'T')
                    + "至"
                    + previous.statEnd().replace(' ', 'T');
        }
        if (TIME_CHANGE.matcher(normalized).find()) {
            return query;
        }
        if (previous != null && previous.statStart() != null && previous.statEnd() != null) {
            return previous.statStart().replace(' ', 'T')
                    + "至"
                    + previous.statEnd().replace(' ', 'T');
        }
        // 没有明确时间词且无历史范围：返回 null 触发时间澄清
        return null;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}

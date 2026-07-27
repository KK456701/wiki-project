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

    /** 表示用户想要“计算结果”而非口径解释的动词/名词。 */
    private static final Pattern WANTS_RESULT = Pattern.compile(
            "计算|结果|数值|试运行|得分|监测|达标|算一遍|算一下|都算");

    /** 命中即视为口径/定义类问题，不进入批量试运行。 */
    private static final Pattern WANTS_DEFINITION = Pattern.compile(
            "定义|口径|公式|是什么|什么意思|怎么算|如何计算|解释|含义");
    /** “按某口径计算”中的“口径”是 Profile 限定词，不是查询口径定义。 */
    private static final Pattern PROFILE_SCOPED_RESULT = Pattern.compile(
            "按.{1,80}(口径|profile).{0,20}(计算|试运行|算)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_CHANGE = Pattern.compile(
            "修改时间|时间改成|统计时间|统计区间|时间范围|到现在|至今|本月|这个月|当月|"
                    + "上月|上个月|今年|去年|"
                    + "[一二三四五六七八九十]{1,3}月份?|"
                    + "\\d{2,4}年|\\d{4}-\\d{1,2}-\\d{1,2}");

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
        BatchRequestSpec detected = detect(query, previous, indicators);
        if (!detected.batch() || detected.allActive() || rules == null) {
            return detected;
        }
        List<Target> enriched = detected.targets().stream()
                .map(target -> withExplicitProfile(query, hospitalId, target))
                .toList();
        return BatchRequestSpec.selected(
                detected.rawQuery(), detected.timeText(), enriched);
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
        if (WANTS_DEFINITION.matcher(normalized).find()
                && !PROFILE_SCOPED_RESULT.matcher(normalized).find()) {
            return BatchRequestSpec.notBatch();
        }
        boolean scopeAll = SCOPE_ALL.matcher(normalized).find();
        boolean wantsResult = WANTS_RESULT.matcher(normalized).find();
        boolean continuesTrial = previous != null
                && previous.valid()
                && "indicator_trial_run".equals(previous.operation());
        String timeText = timeText(query, previous);
        List<Target> explicitTargets = explicitTargets(query, activeIndicators);

        if (scopeAll && (wantsResult || continuesTrial)) {
            return BatchRequestSpec.allActive(query, timeText);
        }
        if (!explicitTargets.isEmpty() && (wantsResult || continuesTrial)) {
            return BatchRequestSpec.selected(query, timeText, explicitTargets);
        }
        if (continuesTrial && isTimeOnlyChange(query) && explicitTargets.isEmpty()) {
            if ("ALL".equals(previous.targetMode())) {
                return BatchRequestSpec.allActive(query, query);
            }
            if ("SUBSET".equals(previous.targetMode()) && previous.targets().size() >= 2) {
                List<Target> remembered = previous.targets().stream()
                        .map(value -> new Target(value.ruleId(), value.ruleName()))
                        .toList();
                return BatchRequestSpec.selected(query, query, remembered);
            }
            if ("SINGLE".equals(previous.targetMode()) && previous.targets().size() == 1) {
                var value = previous.targets().get(0);
                return BatchRequestSpec.selected(
                        query, query, List.of(new Target(value.ruleId(), value.ruleName())));
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

    /**
     * 用户明确写出 Profile 编号或完整名称时缩小到该 Profile；否则保持空值，运行时展开
     * 该指标的全部已审批 Profile。
     */
    public Target withExplicitProfile(String query, String hospitalId, Target target) {
        if (rules == null || query == null || query.isBlank()) {
            return target;
        }
        String normalized = normalize(query);
        return rules.caliberProfiles(target.ruleId(), hospitalId).stream()
                .map(profile -> new Target(
                        target.ruleId(),
                        target.ruleName(),
                        text(profile.get("profile_id")),
                        first(
                                text(profile.get("profile_name")),
                                text(profile.get("label")),
                                text(profile.get("profile_id")))))
                .filter(profile -> profile.profileId() != null
                        && profile.profileName() != null)
                .filter(profile -> normalized.contains(normalize(profile.profileId()))
                        || normalized.contains(normalize(profile.profileName())))
                .sorted(Comparator.comparingInt(
                        profile -> -Math.max(
                                profile.profileId().length(),
                                profile.profileName().length())))
                .findFirst()
                .orElse(target);
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
        return normalized.length() <= 80
                && TIME_CHANGE.matcher(normalized).find()
                && !WANTS_DEFINITION.matcher(normalized).find()
                && !WANTS_RESULT.matcher(normalized).find();
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
        return query;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "");
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null : String.valueOf(value).strip();
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

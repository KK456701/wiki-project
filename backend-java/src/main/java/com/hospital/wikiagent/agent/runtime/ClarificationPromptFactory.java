package com.hospital.wikiagent.agent.runtime;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.planning.ControllerDecision;
import com.hospital.wikiagent.agent.planning.FallbackCategory;
import com.hospital.wikiagent.contract.AgentClarification;
import com.hospital.wikiagent.contract.AgentClarification.Option;
import com.hospital.wikiagent.rules.RuleReadRepository;

/**
 * 将“缺指标、缺时间、目标不清楚”等可恢复停止原因转换为对话式反问。
 *
 * <p>选项完全由服务端目录和固定业务动作生成，不让模型创造 ruleId、口径 profile、
 * SQL 或日期边界。前端提交选择后仍以普通用户消息重新进入完整 Agent 链路。</p>
 */
@Component
public class ClarificationPromptFactory {
    public static final String VERSION = "clarification-prompt-v1";
    private static final int INDICATOR_LIMIT = 500;

    private final RuleReadRepository rules;
    private final Clock clock;

    @Autowired
    public ClarificationPromptFactory(RuleReadRepository rules) {
        this(rules, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    ClarificationPromptFactory(RuleReadRepository rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    public AgentClarification fromDecision(
            ControllerDecision decision,
            RequestPlan plan,
            AgentRunState state,
            String hospitalId,
            String originalQuery) {
        if (decision == null
                || (decision.fallbackCategory() != FallbackCategory.USER_CLARIFICATION
                        && decision.fallbackCategory() != FallbackCategory.BUSINESS_CONFIRMATION)) {
            // 数据库故障、权限拒绝和实施支持不是用户补充文字就能解决的问题，
            // 不能伪装成“等待选择”，仍应按原失败/人工支持路径展示。
            return null;
        }
        String code = safe(decision.code()).toUpperCase(Locale.ROOT);
        if (isIndicatorCode(code)) {
            return indicatorPrompt(
                    code, decision.message(), state, hospitalId, originalQuery, List.of());
        }
        if (isTimeCode(code)) {
            return timePrompt(code, decision.message(), originalQuery, LocalDate.now(clock));
        }
        if (isCaliberCode(code)) {
            return caliberPrompt(code, decision.message(), plan, state, hospitalId, originalQuery);
        }
        if (isIntentCode(code)) {
            return intentPrompt(code, decision.message(), originalQuery);
        }
        if ("DATABASE_ACCESS_CONFLICT".equals(code)) {
            return databasePrompt(code, decision.message(), originalQuery);
        }
        return freeTextPrompt(code, decision.message(), originalQuery);
    }

    public AgentClarification fromResolution(
            HybridIndicatorResolver.Resolution resolution,
            String hospitalId,
            String originalQuery) {
        Map<String, Option> recommended = new LinkedHashMap<>();
        for (var ambiguity : resolution.ambiguities()) {
            for (var candidate : ambiguity.candidates()) {
                recommended.putIfAbsent(candidate.ruleId(), new Option(
                        "indicator:" + candidate.ruleId(),
                        candidate.canonicalName(),
                        candidate.canonicalName() + "（" + candidate.ruleId() + "）",
                        "系统根据“" + ambiguity.mention() + "”召回的候选指标",
                        "推荐匹配"));
                if (recommended.size() >= 3) {
                    break;
                }
            }
            if (recommended.size() >= 3) break;
        }
        boolean multiple = asksForMultipleIndicators(originalQuery);
        return new AgentClarification(
                "INDICATOR_AMBIGUOUS",
                "indicator_selection",
                "先确认要处理的指标",
                multiple
                        ? "请选择一个或多个候选指标，选好后我会继续处理上一条问题。"
                        : "请选择一个候选指标，选择后我会继续处理上一条问题。",
                "我识别到指标名称存在歧义，请选择要继续处理的指标。",
                multiple ? "multiple" : "single",
                List.copyOf(recommended.values()),
                true,
                "也可以输入更完整的正式指标名称",
                resumePrefix(originalQuery, "我选择的指标是："));
    }

    /**
     * Planner 给出了具体指标名但服务端无法解析出 ruleId（例如追问只带模糊名称、
     * 或该名称不在生效目录中）。此时应请用户从目录重新选择指标，
     * 而不是静默回退到上一轮指标。
     */
    public AgentClarification indicatorUnresolved(
            String hospitalId, String originalQuery, String mention) {
        String message = mention == null || mention.isBlank()
                ? "未能将您提到的指标匹配到正式指标，请重新选择。"
                : "未能将“" + mention + "”匹配到正式指标，请重新选择或输入更完整的名称。";
        return indicatorPrompt(
                "INDICATOR_NOT_FOUND", message, null, hospitalId, originalQuery, List.of());
    }

    /**
     * 用户用“这两个指标”“这些指标”等复数指代提问，但没有给出可解析的指标名。
     *
     * <p>单指标流水线无法凭一个复数指代安全地猜出目标；Planner 又常把它误判为
     * 对上一轮单个指标的追问并给出高置信度。这里主动反问，请用户从目录中
     * 明确选择要处理的指标（支持多选）。</p>
     */
    public AgentClarification indicatorMultipleReference(
            String hospitalId, String originalQuery) {
        return indicatorPrompt(
                "INDICATOR_MULTIPLE_REFERENCE",
                "您提到了多个指标，请先选择要处理的指标。",
                null, hospitalId, originalQuery, List.of());
    }

    public AgentClarification intentForAllIndicators(String originalQuery) {
        return intentPrompt(
                "INTENT_AMBIGUOUS",
                "已经确认范围是全部指标，请再选择要执行的操作。",
                originalQuery);
    }

    public AgentClarification intentForIndicator(
            String originalQuery, String indicatorName) {
        return intentPrompt(
                "INTENT_AMBIGUOUS",
                "已经确认指标是“" + safe(indicatorName) + "”，请再选择要执行的操作。",
                originalQuery);
    }

    public AgentClarification missingOperationAndIndicator(String originalQuery) {
        return new AgentClarification(
                "REQUEST_CONTEXT_MISSING",
                "free_text",
                "请补充要处理的操作和指标",
                "已识别到统计时间，但还不知道要处理哪个指标、执行什么操作。",
                "请同时说明指标名称，以及要查询定义、口径、SQL、计算结果还是排查异常。",
                "single",
                List.of(),
                true,
                "例如：计算急会诊及时到位率",
                resumePrefix(originalQuery, "补充操作和指标："));
    }

    /**
     * 判断 query 是否用复数指代引用指标（如“这两个指标”“这些指标”“哪几个指标”）。
     *
     * <p>只匹配“限定词/数量词 + 指标”的紧邻组合，避免误伤“分子分母分别是什么”这类
     * 单指标问句。检测为复数指代且指标不可解析时，服务端应反问而不是猜测。</p>
     */
    public static boolean referencesMultipleIndicators(String query) {
        if (query == null) return false;
        String compact = query.replaceAll("\\s+", "");
        return java.util.regex.Pattern.compile(
                "(这两个|那两个|两个|这些|那些|多个|几个|哪些|哪两个)指标")
                .matcher(compact).find();
    }

    private AgentClarification indicatorPrompt(
            String code,
            String message,
            AgentRunState state,
            String hospitalId,
            String originalQuery,
            List<Option> recommended) {
        Map<String, Option> options = new LinkedHashMap<>();
        if (supportsAllIndicatorSelection(originalQuery)) {
            options.put("indicator:all", new Option(
                    "indicator:all",
                    "全部指标",
                    "全部指标",
                    "计算当前医院全部正式指标",
                    "范围选择"));
        }
        for (Option option : recommended) options.putIfAbsent(option.id(), option);
        for (Option option : stateCandidates(state)) options.putIfAbsent(option.id(), option);
        for (Map<String, String> item : activeIndicators(hospitalId)) {
            String ruleId = safe(item.get("rule_id"));
            String ruleName = safe(item.get("rule_name"));
            if (ruleId.isBlank() || ruleName.isBlank()) continue;
            options.putIfAbsent("indicator:" + ruleId, new Option(
                    "indicator:" + ruleId,
                    ruleName,
                    ruleName + "（" + ruleId + "）",
                    "当前医院可查询的正式指标",
                    "单项指标"));
        }
        boolean multiple = asksForMultipleIndicators(originalQuery);
        String question = options.isEmpty()
                ? "我还不能确定你说的是哪个指标，请输入指标名称或描述它衡量的业务。"
                : multiple
                        ? "请选择一个或多个指标，选好后我会继续处理上一条问题。"
                        : "请选择一个指标，选择后我会继续处理上一条问题。";
        return new AgentClarification(
                code,
                "indicator_selection",
                "先确认要处理的指标",
                question,
                first(message, "不需要记住正式名称；可以搜索关键词，也可以直接浏览全部指标。"),
                multiple ? "multiple" : "single",
                List.copyOf(options.values()),
                true,
                "也可以输入指标名称，例如：急会诊及时到位率",
                resumePrefix(originalQuery, "我选择的指标是："));
    }

    private static AgentClarification timePrompt(
            String code,
            String message,
            String originalQuery,
            LocalDate today) {
        YearMonth month = YearMonth.from(today);
        YearMonth previous = month.minusMonths(1);
        List<Option> options = List.of(
                timeOption("year-to-date", "今年至今",
                        LocalDate.of(today.getYear(), 1, 1), today,
                        "从今年1月1日统计到今天"),
                timeOption("last-year", "最近一年",
                        today.minusYears(1).plusDays(1), today,
                        "从今天往前推一年"),
                timeOption("last-half-year", "最近半年",
                        today.minusMonths(6).plusDays(1), today,
                        "从今天往前推六个月"),
                timeOption("last-3-months", "最近3个月",
                        today.minusMonths(3).plusDays(1), today,
                        "从今天往前推三个月"),
                timeOption("last-month-30", "最近1个月",
                        today.minusMonths(1).plusDays(1), today,
                        "从今天往前推一个月"),
                timeOption("current-month", "本月",
                        month.atDay(1), today,
                        "从本月1日统计到今天"),
                timeOption("previous-month", "上月",
                        previous.atDay(1), previous.atEndOfMonth(),
                        "完整的上一个自然月"),
                timeOption("custom", "自定义范围",
                        today.minusMonths(1), today,
                        "输入自定义开始和结束日期"));
        return new AgentClarification(
                code,
                "time_range",
                "还需要一个统计时间",
                "请选择常用时间范围，或在下方输入自定义开始和结束日期。",
                first(message, "SQL 和指标结果都必须绑定明确统计周期，避免复用上一轮旧结果。"),
                "single",
                options,
                true,
                "例如：2026-01-01 至 2026-03-31",
                resumePrefix(originalQuery, "统计时间为："));
    }

    private AgentClarification caliberPrompt(
            String code,
            String message,
            RequestPlan plan,
            AgentRunState state,
            String hospitalId,
            String originalQuery) {
        String ruleId = first(
                state == null ? "" : state.currentRuleId(),
                plan == null || plan.targetIndicator() == null
                        ? "" : plan.targetIndicator().ruleId());
        Map<String, Option> options = new LinkedHashMap<>();
        for (Option option : caliberStateCandidates(state)) options.putIfAbsent(option.id(), option);
        if (!ruleId.isBlank()) {
            for (Map<String, Object> profile : caliberProfiles(ruleId, hospitalId)) {
                String profileId = safe(profile.get("profile_id"));
                String label = first(safe(profile.get("label")), profileId);
                if (profileId.isBlank()) continue;
                options.putIfAbsent("caliber:" + profileId, new Option(
                        "caliber:" + profileId,
                        label,
                        label,
                        first(safe(profile.get("description")), "已审批候选口径"),
                        "可用候选口径"));
            }
        }
        return new AgentClarification(
                code,
                "caliber_selection",
                "请选择要模拟的口径",
                "候选口径只是试算，不会修改本院当前生效规则。",
                first(message, "只显示当前医院、当前指标可用的已审批候选口径。"),
                "single",
                List.copyOf(options.values()),
                true,
                "例如：按首次入区时间计算",
                resumePrefix(originalQuery, "我希望使用的候选口径是："));
    }

    private static AgentClarification intentPrompt(
            String code,
            String message,
            String originalQuery) {
        List<Option> options = List.of(
                actionOption("explain", "查看定义和计算口径", "解释指标定义、分子、分母和排除条件"),
                actionOption("calculate", "计算具体结果", "按明确时间范围执行受控只读试运行"),
                actionOption("sql", "生成受控 SQL", "生成并校验 SQL，但不执行数据库"),
                actionOption("diagnose", "排查结果或异常", "检查结果异常、计算不一致或数据问题"),
                actionOption("compare", "和上传文件对比", "分析 Excel 与系统结果为什么不同"));
        return new AgentClarification(
                code,
                "intent_selection",
                "你希望我接下来做什么",
                "请选择最接近的目标；如果都不合适，也可以直接补充说明。",
                first(message, "选择动作后，系统会继续使用上一条问题中的指标和上下文。"),
                "single",
                options,
                true,
                "例如：我想看公式，不执行数据库",
                resumePrefix(originalQuery, "我的目标是："));
    }

    private static AgentClarification databasePrompt(
            String code,
            String message,
            String originalQuery) {
        return new AgentClarification(
                code,
                "business_confirmation",
                "是否允许执行只读统计",
                "要得到具体结果，需要访问医院业务库执行受控只读聚合查询。",
                message,
                "single",
                List.of(
                        actionOption("allow-readonly", "允许只读试运行", "继续生成、校验并执行聚合 SQL"),
                        actionOption("explain-only", "只解释口径", "不访问数据库，只说明公式和条件")),
                true,
                "也可以说明你允许的处理范围",
                resumePrefix(originalQuery, "数据库处理要求："));
    }

    private static AgentClarification freeTextPrompt(
            String code,
            String message,
            String originalQuery) {
        return new AgentClarification(
                code,
                "free_text",
                "还需要你补充一点信息",
                first(message, "请补充缺少的信息，我会接着处理上一条问题。"),
                "可以直接按自己的说法回答，不需要使用专业术语。",
                "single",
                List.of(),
                true,
                "补充你的指标、时间、目标或对比要求",
                resumePrefix(originalQuery, "补充信息："));
    }

    private static List<Option> stateCandidates(AgentRunState state) {
        if (state == null) return List.of();
        List<Option> options = new ArrayList<>();
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            ToolResult result = state.lastToolResults().get(index);
            Object raw = result.data().get("matches");
            if (!(raw instanceof List<?> values)) continue;
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> candidate)) continue;
                String id = first(safe(candidate.get("rule_id")), safe(candidate.get("index_code")));
                String label = first(safe(candidate.get("rule_name")), safe(candidate.get("index_name")), id);
                if (id.isBlank()) continue;
                options.add(new Option(
                        "indicator:" + id, label, label + "（" + id + "）",
                        "本轮检索到的候选指标", "推荐匹配"));
            }
            if (!options.isEmpty()) return options;
        }
        return List.of();
    }

    /**
     * 知识目录暂时不可读时仍返回自由文本反问，不能让“帮助用户恢复”本身变成运行错误。
     */
    private List<Map<String, String>> activeIndicators(String hospitalId) {
        try {
            return rules.activeIndicatorNames(hospitalId, INDICATOR_LIMIT);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private List<Map<String, Object>> caliberProfiles(String ruleId, String hospitalId) {
        try {
            return rules.caliberProfiles(ruleId, hospitalId);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static List<Option> caliberStateCandidates(AgentRunState state) {
        if (state == null) return List.of();
        List<Option> options = new ArrayList<>();
        for (int index = state.lastToolResults().size() - 1; index >= 0; index--) {
            Object raw = state.lastToolResults().get(index).data().get("candidates");
            if (!(raw instanceof List<?> values)) continue;
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> candidate)) continue;
                String id = safe(candidate.get("caliber_profile_id"));
                String label = first(safe(candidate.get("caliber_label")), id);
                if (!id.isBlank()) {
                    options.add(new Option(
                            "caliber:" + id, label, label,
                            "本轮解析到的已审批候选口径", "推荐匹配"));
                }
            }
            if (!options.isEmpty()) return options;
        }
        return List.of();
    }

    private static Option actionOption(
            String id,
            String label,
            String description) {
        return new Option("action:" + id, label, label, description, "");
    }

    private static Option timeOption(
            String id,
            String label,
            LocalDate start,
            LocalDate endInclusive,
            String description) {
        return new Option(
                "time:" + id,
                label,
                start + " 至 " + endInclusive,
                description,
                "");
    }

    private static boolean isIndicatorCode(String code) {
        return List.of(
                "INDICATOR_AMBIGUOUS",
                "INDICATOR_NOT_FOUND",
                "TARGET_INDICATOR_AMBIGUOUS",
                "RULE_ID_MISSING").contains(code);
    }

    private static boolean isTimeCode(String code) {
        return List.of("TIME_RANGE_AMBIGUOUS", "STAT_PERIOD_MISSING").contains(code);
    }

    private static boolean isCaliberCode(String code) {
        return List.of(
                "CALIBER_PROFILE_MISSING",
                "CALIBER_PROFILE_NOT_FOUND",
                "CALIBER_PROFILE_AMBIGUOUS").contains(code);
    }

    private static boolean isIntentCode(String code) {
        return List.of(
                "INTENT_AMBIGUOUS",
                "TASK_TYPE_MISMATCH",
                "PLAN_INTENT_MISMATCH").contains(code);
    }

    private static final String RESUME_WRAPPER_HEAD = "继续处理上一条请求“";

    private static boolean asksForMultipleIndicators(String query) {
        String value = safe(query);
        return value.contains("两个")
                || value.contains("多个")
                || value.contains("分别")
                || value.contains("各自")
                || value.contains("这些指标");
    }

    private static String resumePrefix(String originalQuery, String action) {
        String original = stripResumeWrapper(originalQuery);
        if (original.isBlank()) {
            return action;
        }
        if (original.length() > 200) {
            original = original.substring(0, 200);
        }
        return "继续处理上一条请求“" + original + "”。" + action;
    }

    /**
     * 剥离历史澄清回复中的“继续处理上一条请求”包装，还原最内层原始问题。
     *
     * <p>多轮澄清时当前 query 已带上一轮前缀，若不剥离会逐轮嵌套；
     * 前缀可能叠加多层，因此循环剥离后取首个右引号前的内容。</p>
     */
    static String stripResumeWrapper(String query) {
        String value = safe(query);
        boolean stripped = false;
        while (value.startsWith(RESUME_WRAPPER_HEAD)) {
            value = value.substring(RESUME_WRAPPER_HEAD.length());
            stripped = true;
        }
        if (stripped) {
            int close = value.indexOf('”');
            if (close >= 0) {
                value = value.substring(0, close);
            }
        }
        return value.strip();
    }

    private static boolean supportsAllIndicatorSelection(String query) {
        String value = safe(query).replaceAll("\\s+", "");
        return java.util.regex.Pattern.compile(
                "计算|结果|数值|试运行|统计|时间|本月|今年|去年|\\d{2,4}年")
                .matcher(value).find();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}

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
        List<Option> recommended = new ArrayList<>();
        for (var ambiguity : resolution.ambiguities()) {
            for (var candidate : ambiguity.candidates()) {
                recommended.add(new Option(
                        "indicator:" + candidate.ruleId(),
                        candidate.canonicalName(),
                        candidate.canonicalName() + "（" + candidate.ruleId() + "）",
                        "系统根据“" + ambiguity.mention() + "”召回的候选指标",
                        "推荐匹配"));
            }
        }
        return indicatorPrompt(
                "INDICATOR_AMBIGUOUS",
                "我识别到指标名称存在歧义，请选择要继续处理的指标。",
                null,
                hospitalId,
                originalQuery,
                recommended);
    }

    private AgentClarification indicatorPrompt(
            String code,
            String message,
            AgentRunState state,
            String hospitalId,
            String originalQuery,
            List<Option> recommended) {
        Map<String, Option> options = new LinkedHashMap<>();
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
                    "全部指标"));
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
                timeOption("current-month", "本月",
                        month.atDay(1), today,
                        "从本月1日统计到今天"),
                timeOption("previous-month", "上一个自然月",
                        previous.atDay(1), previous.atEndOfMonth(),
                        "完整的上一个自然月"),
                timeOption("last-30-days", "最近30天",
                        today.minusDays(29), today,
                        "包含今天，共30个自然日"));
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

    private static boolean asksForMultipleIndicators(String query) {
        String value = safe(query);
        return value.contains("两个")
                || value.contains("多个")
                || value.contains("分别")
                || value.contains("各自")
                || value.contains("这些指标");
    }

    private static String resumePrefix(String originalQuery, String action) {
        String original = safe(originalQuery);
        if (original.length() > 300) original = original.substring(0, 300) + "…";
        return "继续处理上一条请求“" + original + "”。" + action;
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

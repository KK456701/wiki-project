package com.hospital.wikiagent.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.rules.RuleReadRepository;

/**
 * 服务端确定性拆分 2～3 个并列指标；不让 Planner 决定子任务数量。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class CompoundRequestSplitter {
    public static final String VERSION = "compound-splitter-v2";
    private static final Pattern SEPARATOR = Pattern.compile(
            "(?:\\s*(?:，|,|；|;|、)\\s*|(?:还有|以及|并且|同时|和|与))");
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:助手：)?\\s*##\\s+([^\\r\\n]+)$");
    private static final Pattern FOLLOWUP_REFERENCE = Pattern.compile(
            "这(?:两|三|几)个|两个指标|三个指标|这些指标|它们|他们|分别");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?:从|自|在)?(?:20)?\\d{2}年[^，,；;。？?]{0,24}?(?:到|至|截至|截止到)(?:现在|目前|今天|今日|(?:20)?\\d{2}年?[^，,；;。？?]{0,12})"
                    + "|(?:从|自|在)?(?:1[0-2]|[1-9])月份?[^，,；;。？?]{0,12}?(?:到|至|截至|截止到)(?:现在|目前|今天|今日|(?:1[0-2]|[1-9])月份?)");
    // 纯时间补充追问：相对/绝对时间表达，且不含指标名。比 TIME_RANGE 宽，
    // 覆盖“本月/今年”“统计时间为：2026-01-01 至 2026-07-24”等绝对日期区间。
    private static final Pattern TIME_SUPPLEMENT = Pattern.compile(
            "到现在|至今|本月|这个月|当月|上月|上个月|今年|去年|本年|统计时间|统计区间|时间范围"
                    + "|(?:20)?\\d{2}\\s*[-./]\\s*\\d{1,2}"
                    + "|(?:20)?\\d{2}\\s*年|(?:1[0-2]|[1-9]|[一二三四五六七八九十]{1,3})\\s*月份?");
    private static final List<String> INDICATOR_HINTS = List.of(
            "率", "比例", "指标", "会诊", "转科", "手术", "查房", "抢救", "病历");
    private static final Set<String> SERIAL_TERMS = Set.of(
            "上传", "文件对比", "规则变更", "修改口径", "发布", "审批");
    private final RuleReadRepository rules;

    public CompoundRequestSplitter() {
        this.rules = null;
    }

    @Autowired
    public CompoundRequestSplitter(RuleReadRepository rules) {
        this.rules = rules;
    }

    public SplitResult split(String query, String recentHistory) {
        return split(query, recentHistory, null);
    }

    public SplitResult split(String query, String recentHistory, String hospitalId) {
        return split(query, recentHistory, hospitalId, List.of());
    }

    public SplitResult split(
            String query,
            String recentHistory,
            String hospitalId,
            List<HybridIndicatorResolver.ResolvedIndicator> resolvedIndicators) {
        return split(query, recentHistory, hospitalId, resolvedIndicators, List.of());
    }

    public SplitResult split(
            String query,
            String recentHistory,
            String hospitalId,
            List<HybridIndicatorResolver.ResolvedIndicator> resolvedIndicators,
            List<String> rememberedTargets) {
        String input = query == null ? "" : query.strip();
        if (resolvedIndicators != null && resolvedIndicators.size() >= 2) {
            RequestKind kind = classify(input, recentHistory);
            String time = extractTime(input);
            List<SubtaskSpec> tasks = new ArrayList<>();
            for (int index = 0; index < Math.min(3, resolvedIndicators.size()); index++) {
                var resolved = resolvedIndicators.get(index);
                tasks.add(new SubtaskSpec(
                        index + 1, resolved.canonicalName(),
                        childQuery(resolved.canonicalName(), kind, time), resolved));
            }
            boolean serial = SERIAL_TERMS.stream().anyMatch(input::contains);
            return new SplitResult(List.copyOf(tasks), kind, time, serial, false);
        }
        List<String> clauses = explicitClauses(input);
        boolean followup = false;
        if (clauses.isEmpty()) {
            clauses = mentionedIndicators(input, hospitalId);
        }
        if (clauses.isEmpty() && FOLLOWUP_REFERENCE.matcher(input).find()) {
            clauses = historyTargets(recentHistory);
            if (clauses.isEmpty()) {
                clauses = rememberedCompound(rememberedTargets);
            }
            followup = !clauses.isEmpty();
        }
        if (clauses.isEmpty() && isTimeSupplement(input)) {
            // 追问只补统计时间（如“从26年一月到现在”“本月”“统计时间为：2026-01-01 至 …”）且自身不含指标名时，
            // 若上一轮是复合澄清，继承那批指标重新展开为复合，避免补时间后退化成单指标。
            // 先从历史 ## 小节恢复；历史被截断（长 SQL 挤掉第二个小节）时，回退到结构态记住的整批指标。
            clauses = historyTargets(recentHistory);
            if (clauses.isEmpty()) {
                clauses = rememberedCompound(rememberedTargets);
            }
            followup = !clauses.isEmpty();
        }
        if (clauses.isEmpty()) {
            return SplitResult.none();
        }
        RequestKind kind = classify(input, recentHistory);
        String time = extractTime(input);
        if (time == null && followup && isTimeSupplement(input)) {
            // splitter 的 TIME_RANGE 正则比 TimeRangeResolver 窄（如“本月”不匹配），
            // 对纯时间补充直接把整句作为统计周期交给下游确定性解析。
            time = input;
        }
        List<SubtaskSpec> tasks = new ArrayList<>();
        for (int index = 0; index < clauses.size(); index++) {
            String target = followup ? clauses.get(index) : target(clauses.get(index));
            tasks.add(new SubtaskSpec(index + 1, target, childQuery(target, kind, time), null));
        }
        boolean serial = SERIAL_TERMS.stream().anyMatch(input::contains);
        return new SplitResult(List.copyOf(tasks), kind, time, serial, followup);
    }

    private List<String> mentionedIndicators(String query, String hospitalId) {
        if (rules == null || hospitalId == null || hospitalId.isBlank()) {
            return List.of();
        }
        try {
            return rules.activeIndicatorNames(hospitalId, 500).stream()
                    .map(value -> value.get("rule_name"))
                    .filter(name -> name != null && !name.isBlank() && query.contains(name))
                    .distinct()
                    .sorted(java.util.Comparator.comparingInt(query::indexOf))
                    .limit(3)
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static List<String> explicitClauses(String query) {
        String[] values = SEPARATOR.split(query);
        List<String> clauses = new ArrayList<>();
        for (String value : values) {
            String clause = value.strip().replaceAll("^[，,；;。\\s]+|[，,；;。？?\\s]+$", "");
            if (!clause.isBlank()) {
                clauses.add(clause);
            }
        }
        if (clauses.size() < 2 || clauses.size() > 3
                || clauses.stream().anyMatch(value -> !looksLikeIndicator(value))) {
            return List.of();
        }
        return List.copyOf(clauses);
    }

    private static List<String> historyTargets(String history) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = HEADING.matcher(history == null ? "" : history);
        while (matcher.find()) {
            String value = matcher.group(1).strip();
            if (!value.startsWith("子任务") && looksLikeIndicator(value)) {
                values.add(value);
            }
        }
        if (values.size() < 2) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values);
        return List.copyOf(result.subList(Math.max(0, result.size() - 3), result.size()));
    }

    private static boolean looksLikeIndicator(String value) {
        String compact = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return INDICATOR_HINTS.stream().anyMatch(compact::contains);
    }

    /**
     * 历史 ## 小节因截断丢失时的兜底：使用上一轮复合轮写入结构态的整批指标。
     * 仅当记住的指标 ≥2 个时才重展开，天然排除单指标会话。
     */
    private static List<String> rememberedCompound(List<String> rememberedTargets) {
        if (rememberedTargets == null || rememberedTargets.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> valid = new LinkedHashSet<>();
        for (String value : rememberedTargets) {
            if (value != null && !value.isBlank() && looksLikeIndicator(value)) {
                valid.add(value.strip());
            }
        }
        if (valid.size() < 2) {
            return List.of();
        }
        List<String> result = new ArrayList<>(valid);
        return List.copyOf(result.subList(0, Math.min(3, result.size())));
    }

    /**
     * 判断追问是否为“纯时间补充”：自身不含指标名、短句、且包含时间表达。
     * 用于复合澄清后的续接：用户只补了时间时仍应沿用上一轮的整批指标。
     */
    private static boolean isTimeSupplement(String query) {
        if (query == null || query.isBlank() || query.length() > 40 || looksLikeIndicator(query)) {
            return false;
        }
        return TIME_SUPPLEMENT.matcher(query).find();
    }

    private static String target(String clause) {
        String value = TIME_RANGE.matcher(clause).replaceAll("");
        value = value.replaceAll(
                "(?i)(?:的)?(?:具体)?(?:结果|数值|指标值|sql脚本|sql)(?:怎么(?:算|写|计算)|如何(?:计算|写)|是多少)?$",
                "");
        value = value.replaceAll("(?:怎么(?:算|计算)|如何计算|的公式|公式是什么|是多少)[？?]?$", "");
        value = value.replaceAll("^(?:请|帮我|再|查询|查一下|计算|统计|查看|看看)+", "");
        value = value.replaceAll("(?:的)?结果$", "");
        value = value.strip().replaceAll("^[，,；;。\\s]+|[，,；;。？?\\s]+$", "");
        return value.isBlank() ? clause.strip() : value;
    }

    private static RequestKind classify(String query, String recentHistory) {
        String compact = query.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        // 复合请求常以“我选择/这两个指标”等指代形式出现，当前句不含意图关键词。
        // 此时回退到最近一轮用户消息，继承原始意图（如“SQL 怎么写”），避免子任务降级为口径解释。
        String context = compact + "\n" + lastUserText(recentHistory)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        // “执行/运行/跑”明确要求算出数值：即使句中出现“sql”一词（如“执行这两个sql”），
        // 也应试运行而非仅生成 SQL；但“怎么写/如何写/生成”仍属于 SQL 准备。
        if (List.of("执行", "运行", "跑").stream().anyMatch(context::contains)
                && List.of("怎么写", "如何写", "生成").stream().noneMatch(context::contains)) {
            return RequestKind.TRIAL_RUN;
        }
        if (context.contains("sql")) {
            return RequestKind.SQL_PREPARE;
        }
        if (List.of("异常", "原因", "不一致", "算不对", "排查", "诊断").stream()
                .anyMatch(context::contains)) {
            return RequestKind.DIAGNOSIS;
        }
        if (List.of("结果", "数值", "多少", "是多少", "计算一下", "算一下", "统计").stream()
                .anyMatch(context::contains) || extractTime(query) != null) {
            return RequestKind.TRIAL_RUN;
        }
        return RequestKind.RULE_EXPLANATION;
    }

    /**
     * 取最近一轮用户消息原文。复合拆分只应继承“上一句”的意图，
     * 避免被更早的历史轮次误导。
     */
    private static String lastUserText(String recentHistory) {
        if (recentHistory == null || recentHistory.isBlank()) {
            return "";
        }
        String last = "";
        for (String line : recentHistory.split("\\r?\\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("用户：")) {
                last = trimmed.substring("用户：".length()).strip();
            } else if (trimmed.startsWith("用户:")) {
                last = trimmed.substring("用户:".length()).strip();
            }
        }
        return last;
    }

    private static String extractTime(String query) {
        Matcher matcher = TIME_RANGE.matcher(query);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private static String childQuery(String target, RequestKind kind, String time) {
        String period = time == null ? "" : "，统计周期" + time;
        return switch (kind) {
            case SQL_PREPARE -> "生成“" + target + "”的受控 SQL" + period;
            case DIAGNOSIS -> "诊断“" + target + "”的异常或差异原因" + period;
            case TRIAL_RUN -> "计算“" + target + "”的具体结果" + period;
            case RULE_EXPLANATION -> "解释“" + target + "”的定义、公式和本院口径";
        };
    }

    public enum RequestKind {
        RULE_EXPLANATION,
        TRIAL_RUN,
        SQL_PREPARE,
        DIAGNOSIS
    }

    public record SubtaskSpec(
            int index,
            String target,
            String query,
            HybridIndicatorResolver.ResolvedIndicator resolvedIndicator) {
        public SubtaskSpec(int index, String target, String query) {
            this(index, target, query, null);
        }
    }

    public record SplitResult(
            List<SubtaskSpec> tasks,
            RequestKind kind,
            String commonTimeExpression,
            boolean serialRequired,
            boolean followup) {
        public SplitResult {
            tasks = List.copyOf(tasks);
        }

        static SplitResult none() {
            return new SplitResult(List.of(), RequestKind.RULE_EXPLANATION, null, false, false);
        }

        public boolean compound() {
            return tasks.size() >= 2;
        }
    }
}

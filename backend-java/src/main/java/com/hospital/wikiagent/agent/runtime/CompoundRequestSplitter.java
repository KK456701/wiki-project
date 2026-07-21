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

/** 服务端确定性拆分 2～3 个并列指标；不让 Planner 决定子任务数量。 */
@Component
public class CompoundRequestSplitter {
    public static final String VERSION = "compound-splitter-v1";
    private static final Pattern SEPARATOR = Pattern.compile(
            "(?:\\s*(?:，|,|；|;|、)\\s*|(?:还有|以及|并且|同时|和|与))");
    private static final Pattern HEADING = Pattern.compile("(?m)^(?:助手：)?\\s*##\\s+([^\\r\\n]+)$");
    private static final Pattern FOLLOWUP_REFERENCE = Pattern.compile(
            "这(?:两|三|几)个|两个指标|三个指标|这些指标|它们|他们|分别");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?:从|自|在)?(?:20)?\\d{2}年[^，,；;。？?]{0,24}?(?:到|至|截至|截止到)(?:现在|目前|今天|今日|(?:20)?\\d{2}年?[^，,；;。？?]{0,12})"
                    + "|(?:从|自|在)?(?:1[0-2]|[1-9])月份?[^，,；;。？?]{0,12}?(?:到|至|截至|截止到)(?:现在|目前|今天|今日|(?:1[0-2]|[1-9])月份?)");
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
        String input = query == null ? "" : query.strip();
        List<String> clauses = explicitClauses(input);
        boolean followup = false;
        if (clauses.isEmpty()) {
            clauses = mentionedIndicators(input, hospitalId);
        }
        if (clauses.isEmpty() && FOLLOWUP_REFERENCE.matcher(input).find()) {
            clauses = historyTargets(recentHistory);
            followup = !clauses.isEmpty();
        }
        if (clauses.isEmpty()) {
            return SplitResult.none();
        }
        RequestKind kind = classify(input);
        String time = extractTime(input);
        List<SubtaskSpec> tasks = new ArrayList<>();
        for (int index = 0; index < clauses.size(); index++) {
            String target = followup ? clauses.get(index) : target(clauses.get(index));
            tasks.add(new SubtaskSpec(index + 1, target, childQuery(target, kind, time)));
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

    private static RequestKind classify(String query) {
        String compact = query.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compact.contains("sql")) {
            return RequestKind.SQL_PREPARE;
        }
        if (List.of("异常", "原因", "不一致", "算不对", "排查", "诊断").stream()
                .anyMatch(compact::contains)) {
            return RequestKind.DIAGNOSIS;
        }
        if (List.of("结果", "数值", "多少", "是多少", "计算一下", "算一下", "统计").stream()
                .anyMatch(compact::contains) || extractTime(query) != null) {
            return RequestKind.TRIAL_RUN;
        }
        return RequestKind.RULE_EXPLANATION;
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

    public record SubtaskSpec(int index, String target, String query) {
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

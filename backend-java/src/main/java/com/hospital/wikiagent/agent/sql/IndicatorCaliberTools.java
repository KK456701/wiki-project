package com.hospital.wikiagent.agent.sql;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.runtime.AgentRunState;
import com.hospital.wikiagent.agent.runtime.ToolResult;
import com.hospital.wikiagent.agent.tools.ToolExecutionContext;
import com.hospital.wikiagent.rules.RuleReadRepository;

/**
 * 执行已治理候选口径的解析、SQL 准备和只读试运行。
 *
 * <p>用户只能提供“入区时间”等业务表达，实际 profile、字段角色覆盖和参数覆盖必须
 * 来自 Wiki 已审批配置。该工具不接受任意 SQL 或物理字段名，并复用
 * {@link IndicatorSqlTools} 的只读安全校验与 SQL 对象链。</p>
 */
@Component
public class IndicatorCaliberTools {

    private final RuleReadRepository rules;
    private final IndicatorSqlTools sqlTools;

    public IndicatorCaliberTools(
            RuleReadRepository rules,
            IndicatorSqlTools sqlTools) {
        this.rules = rules;
        this.sqlTools = sqlTools;
    }

    public ToolResult resolve(ResolveInput input, ToolExecutionContext context) {
        List<Map<String, Object>> candidates = eligibleProfiles(
                input.ruleId(),
                context.agentContext().hospitalId(),
                input.statStartTime(),
                input.statEndTime());
        List<Map<String, Object>> matched;
        if (input.profileId() != null) {
            matched = candidates.stream()
                    .filter(item -> input.profileId().equals(text(item.get("profile_id"))))
                    .toList();
        } else {
            int best = candidates.stream()
                    .mapToInt(item -> score(item, input.rawCaliber()))
                    .max()
                    .orElse(0);
            matched = best == 0 ? List.of() : candidates.stream()
                    .filter(item -> score(item, input.rawCaliber()) >= best - 5)
                    .toList();
        }
        if (matched.isEmpty()) {
            return ToolResult.failure(
                    "validation_failed",
                    "CALIBER_PROFILE_NOT_FOUND",
                    "未找到适用于当前医院和统计周期的已审批候选口径。",
                    false);
        }
        if (matched.size() > 1) {
            return new ToolResult(
                    false,
                    "validation_failed",
                    "CALIBER_PROFILE_AMBIGUOUS",
                    "找到多个候选口径，请明确选择：" + matched.stream()
                            .map(item -> text(item.get("label")))
                            .filter(value -> !value.isBlank())
                            .limit(3)
                            .reduce((left, right) -> left + "、" + right)
                            .orElse("候选口径"),
                    Map.of("candidates", matched.stream().map(this::safeProfile).toList()),
                    false,
                    false,
                    List.of());
        }
        Map<String, Object> profile = matched.get(0);
        AgentRunState state = context.runState();
        state.currentCaliber(
                text(profile.get("profile_id")),
                text(profile.get("label")));
        Map<String, Object> data = new LinkedHashMap<>(safeProfile(profile));
        data.put("rule_id", input.ruleId());
        appendCurrentRule(data, input.ruleId(), context);
        if (input.statStartTime() != null) data.put("stat_start", input.statStartTime());
        if (input.statEndTime() != null) data.put("stat_end", input.statEndTime());
        return ToolResult.success(
                "CALIBER_PROFILE_RESOLVED",
                "已确认候选口径：“" + data.get("caliber_label") + "”。",
                data);
    }

    public ToolResult prepare(PrepareInput input, ToolExecutionContext context) {
        Map<String, Object> profile = requireProfile(
                input.ruleId(), input.profileId(), context, input.statStartTime(), input.statEndTime());
        if (profile.isEmpty()) {
            return ToolResult.failure(
                    "validation_failed",
                    "CALIBER_PROFILE_NOT_AVAILABLE",
                    "候选口径已失效或不适用于当前医院和统计周期。",
                    false);
        }
        ToolResult prepared = sqlTools.prepareDiagnostic(
                new IndicatorSqlTools.PrepareInput(
                        input.ruleId(), input.statStartTime(), input.statEndTime()),
                input.profileId(),
                objectMap(profile.get("parameter_overrides")),
                objectMap(profile.get("field_role_overrides")),
                context);
        if (!prepared.ok()) return prepared;
        Map<String, Object> data = new LinkedHashMap<>(prepared.data());
        data.putAll(safeProfile(profile));
        appendCurrentRule(data, input.ruleId(), context);
        context.runState().currentCaliber(
                input.profileId(), text(profile.get("label")));
        return ToolResult.success(
                "CALIBER_SQL_PREPARED",
                "候选口径 SQL 已完成确定性生成和只读安全校验。",
                data);
    }

    public ToolResult trial(TrialInput input, ToolExecutionContext context) {
        AgentRunState state = context.runState();
        if (state.currentCaliberProfileId() == null
                || !state.currentCaliberProfileId().equals(input.profileId())) {
            return ToolResult.failure(
                    "validation_failed",
                    "CALIBER_PROFILE_CHAIN_MISMATCH",
                    "候选口径与当前已校验 SQL 链不一致，请重新准备。",
                    false);
        }
        ToolResult trial = sqlTools.trial(
                new IndicatorSqlTools.TrialInput(input.sqlId()), context);
        if (!trial.ok()) return trial;
        Map<String, Object> profile = requireProfile(
                text(trial.data().get("rule_id")),
                input.profileId(),
                context,
                text(trial.data().get("stat_start")),
                text(trial.data().get("stat_end")));
        if (profile.isEmpty()) {
            return ToolResult.failure(
                    "validation_failed",
                    "CALIBER_PROFILE_CHAIN_MISMATCH",
                    "试运行完成，但候选口径配置已变化，本轮结果不能用于回答。",
                    false);
        }
        Map<String, Object> data = new LinkedHashMap<>(trial.data());
        data.putAll(safeProfile(profile));
        appendCurrentRule(data, text(trial.data().get("rule_id")), context);
        data.put("caliber_sql_id", input.sqlId());
        return ToolResult.success(
                "CALIBER_TRIAL_RUN_COMPLETED",
                "候选口径只读试运行完成，已获得聚合结果。",
                data);
    }

    private Map<String, Object> requireProfile(
            String ruleId,
            String profileId,
            ToolExecutionContext context,
            String start,
            String end) {
        if (ruleId == null || ruleId.isBlank() || profileId == null || profileId.isBlank()) {
            return Map.of();
        }
        return eligibleProfiles(ruleId, context.agentContext().hospitalId(), start, end).stream()
                .filter(item -> profileId.equals(text(item.get("profile_id"))))
                .findFirst()
                .orElse(Map.of());
    }

    private List<Map<String, Object>> eligibleProfiles(
            String ruleId,
            String hospitalId,
            String start,
            String end) {
        LocalDate startDate = date(start);
        LocalDate endDate = date(end);
        return rules.caliberProfiles(ruleId, hospitalId).stream()
                .filter(profile -> applies(profile, startDate, endDate))
                .sorted(Comparator.comparing(item -> text(item.get("profile_id"))))
                .toList();
    }

    private static boolean applies(
            Map<String, Object> profile,
            LocalDate start,
            LocalDate end) {
        LocalDate effectiveFrom = date(text(profile.get("effective_from")));
        LocalDate effectiveTo = date(text(profile.get("effective_to")));
        if (start != null && effectiveTo != null && !start.isBefore(effectiveTo)) return false;
        return end == null || effectiveFrom == null || end.isAfter(effectiveFrom);
    }

    private Map<String, Object> safeProfile(Map<String, Object> profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caliber_profile_id", text(profile.get("profile_id")));
        result.put("caliber_label", text(profile.get("label")));
        result.put("caliber_version", text(profile.get("source_version")));
        result.put("caliber_source_level", text(profile.get("source_level")));
        result.put("caliber_definition", text(profile.get("caliber_definition")));
        result.put("caliber_numerator_rule", text(profile.get("numerator_rule")));
        result.put("caliber_denominator_rule", text(profile.get("denominator_rule")));
        result.put("period_anchor_label", text(profile.get("period_anchor_label")));
        result.put("elapsed_anchor_label", text(profile.get("elapsed_anchor_label")));
        result.put("field_role_overrides", objectMap(profile.get("field_role_overrides")));
        result.put("difference_dimensions", stringList(profile.get("difference_dimensions")));
        return result;
    }

    /**
     * 将当前生效规则的最小身份信息附加到候选 Evidence。
     * 候选 profile 只表示“如何模拟”，不能替代正式规则版本。
     */
    private void appendCurrentRule(
            Map<String, Object> target,
            String ruleId,
            ToolExecutionContext context) {
        Map<String, Object> current = rules.effectiveRule(
                ruleId, context.agentContext().hospitalId());
        target.put("current_rule_id", ruleId);
        target.put("current_rule_name", text(current.get("rule_name")));
        target.put("current_rule_version", firstText(
                current.get("hospital_version"), current.get("version")));
    }

    private static int score(Map<String, Object> profile, String raw) {
        String query = normalize(raw);
        if (query.isBlank()) return 0;
        List<String> names = new ArrayList<>();
        names.add(text(profile.get("label")));
        names.addAll(stringList(profile.get("aliases")));
        names.addAll(stringList(profile.get("evidence_keywords")));
        names.addAll(stringList(profile.get("difference_dimensions")));
        int score = 0;
        for (String name : names) {
            String candidate = normalize(name);
            if (candidate.isBlank()) continue;
            if (query.equals(candidate)) score = Math.max(score, 110);
            else if (query.contains(candidate)) score = Math.max(score, 100);
            else if (candidate.contains(query)) score = Math.max(score, 80);
            else if (query.contains("入区") && candidate.contains("入区")) score = Math.max(score, 95);
            else {
                double similarity = semanticSimilarity(query, candidate);
                if (similarity >= 0.45) {
                    score = Math.max(score, (int) Math.round(40 + similarity * 40));
                }
            }
        }
        return score;
    }

    /**
     * 本地字符集合相似度用于名称未完全命中时的轻量语义召回。
     * 它不调用外部向量库，也不能越过已审批候选列表。
     */
    private static double semanticSimilarity(String left, String right) {
        java.util.Set<Integer> leftChars = left.codePoints().boxed()
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<Integer> rightChars = right.codePoints().boxed()
                .collect(java.util.stream.Collectors.toSet());
        if (leftChars.isEmpty() || rightChars.isEmpty()) return 0;
        java.util.Set<Integer> overlap = new java.util.HashSet<>(leftChars);
        overlap.retainAll(rightChars);
        return overlap.size() * 2.0 / (leftChars.size() + rightChars.size());
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(normalized.substring(0, Math.min(10, normalized.length())));
            } catch (RuntimeException ignoredDate) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<String> result = new ArrayList<>();
        iterable.forEach(item -> {
            String text = text(item);
            if (!text.isBlank()) result.add(text);
        });
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。、“”‘’：:；;？?（）()【】\\[\\]_-]+", "");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String candidate = text(value);
            if (!candidate.isBlank()) return candidate;
        }
        return "";
    }

    public record ResolveInput(
            String ruleId,
            String rawCaliber,
            String profileId,
            String statStartTime,
            String statEndTime) {
        public ResolveInput {
            ruleId = required(ruleId, "规则编号");
            rawCaliber = rawCaliber == null ? "" : rawCaliber.strip();
            profileId = optional(profileId);
            statStartTime = optional(statStartTime);
            statEndTime = optional(statEndTime);
            if (rawCaliber.isBlank() && profileId == null) {
                throw new IllegalArgumentException("候选口径原文和编号不能同时为空");
            }
        }
    }

    public record PrepareInput(
            String ruleId,
            String profileId,
            String statStartTime,
            String statEndTime) {
        public PrepareInput {
            ruleId = required(ruleId, "规则编号");
            profileId = required(profileId, "候选口径编号");
            statStartTime = required(statStartTime, "统计开始时间");
            statEndTime = required(statEndTime, "统计结束时间");
        }
    }

    public record TrialInput(String sqlId, String profileId) {
        public TrialInput {
            sqlId = required(sqlId, "SQL 对象编号");
            profileId = required(profileId, "候选口径编号");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

package com.hospital.wikiagent.agent.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.ir.PlanIntent;
import com.hospital.wikiagent.agent.ir.RequestPlan;
import com.hospital.wikiagent.agent.ir.RequestedOutput;
import com.hospital.wikiagent.rules.RuleReadRepository;

/**
 * 对照用户原始问题和 Planner 计划，检查任务类型与输出目标是否真正一致。
 *
 * <p>Planner 自己生成的 intent 与 requested_outputs 只能证明 JSON 自洽，不能证明
 * 它回答了用户原话。本校验器使用高置信业务信号和 Wiki 已审批候选口径发现方向性
 * 错误；只有无法确定的复杂表达才交给 LLM 审核，正常请求不会增加模型调用。</p>
 */
@Component
public class PlanGoalAlignmentValidator {

    private final RuleReadRepository rules;

    public PlanGoalAlignmentValidator(RuleReadRepository rules) {
        this.rules = rules;
    }

    public AlignmentDecision assess(
            String userQuery,
            RequestPlan plan,
            String hospitalId) {
        String query = normalize(userQuery);
        if (query.isBlank()) {
            return AlignmentDecision.pass();
        }

        // “根据什么口径算的”“当前是按入院还是入区”都在追问现行规则。
        // 这类问题与“根据入区怎么算”只有几个字的差异，小模型很容易误判为候选模拟，
        // 因此必须先用确定性规则区分，再允许 Planner 计划进入 IR。
        boolean currentCaliberQuestion = containsAny(
                query,
                "什么口径",
                "哪个口径",
                "哪种口径",
                "口径是什么",
                "口径依据")
                || query.contains("还是")
                || ((query.contains("当前") || query.contains("现在"))
                        && (query.contains("按") || query.contains("根据"))
                        && !query.contains("如果"));
        if (currentCaliberQuestion
                && plan.intent() == PlanIntent.INDICATOR_CALIBER_SIMULATION) {
            return AlignmentDecision.mismatch(
                    "TASK_TYPE_MISMATCH",
                    "用户在追问当前生效口径，但 Planner 错误生成了候选口径模拟计划。",
                    correctedCurrentRulePlan(plan),
                    List.of());
        }
        boolean alternateCaliber = !currentCaliberQuestion
                && containsAny(query, "入区", "首次入区")
                && containsAny(query, "按", "根据", "如果", "改成", "改为", "换成", "那");
        if (alternateCaliber) {
            List<Map<String, Object>> candidates = candidateProfiles(
                    plan.targetIndicator().ruleId(), hospitalId, query);
            if (candidates.size() == 1) {
                Map<String, Object> candidate = candidates.get(0);
                String profileId = text(candidate.get("profile_id"));
                boolean correctIntent = plan.intent() == PlanIntent.INDICATOR_CALIBER_SIMULATION;
                boolean correctProfile = profileId.equals(plan.targetCaliber().profileId())
                        || profileId.equals(text(plan.targetCaliber().profileId()));
                boolean wantsSql = containsAny(query, "sql", "脚本");
                boolean wantsTrial = !wantsSql && hasPeriod(plan);
                RequestedOutput expectedOutput = wantsSql
                        ? RequestedOutput.CALIBER_PREPARED_SQL_HANDLE
                        : wantsTrial
                                ? RequestedOutput.CALIBER_TRIAL_RESULT
                                : RequestedOutput.CALIBER_EXPLANATION;
                boolean correctOutput = plan.requestedOutputs().contains(expectedOutput);
                if (correctIntent && correctProfile && correctOutput) {
                    return AlignmentDecision.pass();
                }
                RequestPlan corrected = correctedCaliberPlan(
                        plan, candidate, wantsTrial, wantsSql);
                return AlignmentDecision.mismatch(
                        "TASK_TYPE_MISMATCH",
                        "用户要求按“" + candidate.get("label")
                                + "”候选口径计算，但 Planner 生成了 "
                                + plan.intent().value() + "，无法执行候选口径试运行。",
                        corrected,
                        candidates);
            }
            if (candidates.size() > 1) {
                return AlignmentDecision.review(
                        "用户要求切换统计口径，但存在多个已审批候选，需要审核目标口径。",
                        candidates);
            }
            return AlignmentDecision.review(
                    "用户表达了候选口径计算目标，但没有找到可唯一确认的已审批口径。",
                    List.of());
        }

        // 这些高置信词用于发现“只解释规则”这一类明显任务类型错误。
        if (containsAny(query, "sql怎么写", "sql脚本", "生成sql")
                && plan.intent() != PlanIntent.INDICATOR_SQL_PREPARE
                && !(plan.intent() == PlanIntent.INDICATOR_CALIBER_SIMULATION
                        && plan.requestedOutputs().contains(
                                RequestedOutput.CALIBER_PREPARED_SQL_HANDLE))) {
            return AlignmentDecision.mismatch(
                    "TASK_TYPE_MISMATCH",
                    "用户明确要求生成 SQL，但计划不是 SQL 准备任务。",
                    null,
                    List.of());
        }
        if (containsAny(query, "为什么不一样", "为什么不一致", "差异记录", "对比结果")
                && plan.intent() != PlanIntent.INDICATOR_DIFFERENCE_DIAGNOSIS) {
            return AlignmentDecision.mismatch(
                    "TASK_TYPE_MISMATCH",
                    "用户明确要求比较双方结果差异，但计划不是差异诊断任务。",
                    null,
                    List.of());
        }
        if (containsAny(query, "具体结果", "结果是多少", "算一下结果", "计算结果")
                && plan.intent() == PlanIntent.RULE_EXPLANATION) {
            return AlignmentDecision.mismatch(
                    "TASK_TYPE_MISMATCH",
                    "用户明确要求实际数值，但计划只读取规则解释。",
                    null,
                    List.of());
        }
        if (containsAny(query, "换一种口径", "另一种口径", "假设口径", "如果改用")) {
            return AlignmentDecision.review(
                    "用户可能要求候选口径模拟，需要结合上下文审核 Planner 计划。",
                    candidateProfiles(plan.targetIndicator().ruleId(), hospitalId, query));
        }
        return AlignmentDecision.pass();
    }

    /**
     * Replanner 仍未纠正高置信错误时，只有服务端已得到唯一安全计划才允许兜底。
     */
    public RequestPlan deterministicFallback(AlignmentDecision decision) {
        return decision == null ? null : decision.suggestedPlan();
    }

    public RequestPlan correctionForReviewedProfile(
            RequestPlan original,
            AlignmentDecision decision,
            String profileId,
            String userQuery) {
        if (decision == null || profileId == null || profileId.isBlank()) return null;
        boolean wantsSql = containsAny(normalize(userQuery), "sql", "脚本");
        return decision.candidates().stream()
                .filter(item -> profileId.equals(text(item.get("profile_id"))))
                .findFirst()
                .map(profile -> correctedCaliberPlan(
                        original, profile, !wantsSql && hasPeriod(original), wantsSql))
                .orElse(null);
    }

    private RequestPlan correctedCaliberPlan(
            RequestPlan original,
            Map<String, Object> candidate,
            boolean withTrial,
            boolean withSql) {
        String label = text(candidate.get("label"));
        String profileId = text(candidate.get("profile_id"));
        List<RequestedOutput> outputs = withSql
                ? List.of(
                        RequestedOutput.CALIBER_EXPLANATION,
                        RequestedOutput.CALIBER_PREPARED_SQL_HANDLE)
                : withTrial
                ? List.of(
                        RequestedOutput.CALIBER_EXPLANATION,
                        RequestedOutput.CALIBER_TRIAL_RESULT)
                : List.of(RequestedOutput.CALIBER_EXPLANATION);
        return new RequestPlan(
                RequestPlan.VERSION,
                PlanIntent.INDICATOR_CALIBER_SIMULATION,
                withSql
                        ? "按“" + label + "”候选口径生成受控 SQL"
                        : withTrial
                        ? "按“" + label + "”候选口径进行只读试运行并解释结果"
                        : "解释“" + label + "”候选口径",
                original.targetIndicator(),
                new RequestPlan.TargetCaliber(label, profileId),
                original.timeExpression(),
                outputs,
                original.constraints(),
                original.semanticAmbiguities());
    }

    /**
     * 将“当前按什么口径”恢复为现行规则解释。
     *
     * <p>统计周期仍保留在结构化计划中，便于回答说明该口径对应上一轮结果；
     * 但本轮不重复执行数据库，也不会继承候选 profile。</p>
     */
    private RequestPlan correctedCurrentRulePlan(RequestPlan original) {
        return new RequestPlan(
                RequestPlan.VERSION,
                PlanIntent.RULE_EXPLANATION,
                "解释当前生效口径、分子分母条件及版本依据",
                original.targetIndicator(),
                new RequestPlan.TargetCaliber("", null),
                original.timeExpression(),
                List.of(
                        RequestedOutput.DEFINITION,
                        RequestedOutput.FORMULA),
                original.constraints(),
                original.semanticAmbiguities());
    }

    private List<Map<String, Object>> candidateProfiles(
            String ruleId,
            String hospitalId,
            String query) {
        if (ruleId == null || ruleId.isBlank()) {
            return List.of();
        }
        List<ScoredProfile> scored = new ArrayList<>();
        for (Map<String, Object> profile : rules.diagnosticProfiles(ruleId, hospitalId)) {
            int score = profileScore(profile, query);
            if (score > 0) {
                scored.add(new ScoredProfile(score, profile));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredProfile::score).reversed()
                .thenComparing(value -> text(value.profile().get("profile_id"))));
        if (scored.isEmpty()) {
            return List.of();
        }
        int best = scored.get(0).score();
        return scored.stream()
                .filter(value -> value.score() >= best - 5)
                .map(ScoredProfile::profile)
                // Wiki 中 effective_to 等可选字段允许为 null。Map.copyOf 会拒绝
                // 任何 null 键值并在一致性校验阶段抛出 NPE，因此这里保留原始
                // YAML 语义，只通过不可修改视图防止调用方篡改候选配置。
                .map(value -> Collections.unmodifiableMap(new LinkedHashMap<>(value)))
                .toList();
    }

    private static int profileScore(Map<String, Object> profile, String normalizedQuery) {
        int score = 0;
        List<String> names = new ArrayList<>();
        add(names, profile.get("label"));
        addAll(names, profile.get("aliases"));
        addAll(names, profile.get("evidence_keywords"));
        addAll(names, profile.get("difference_dimensions"));
        for (String name : names) {
            String candidate = normalize(name);
            if (candidate.isBlank()) continue;
            if (normalizedQuery.contains(candidate)) {
                score = Math.max(score, 100);
            } else if (candidate.contains(normalizedQuery)) {
                score = Math.max(score, 80);
            } else if (candidate.contains("入区") && normalizedQuery.contains("入区")) {
                score = Math.max(score, 95);
            } else {
                double similarity = semanticSimilarity(normalizedQuery, candidate);
                if (similarity >= 0.45) {
                    score = Math.max(score, (int) Math.round(40 + similarity * 40));
                }
            }
        }
        return score;
    }

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

    private static boolean hasPeriod(RequestPlan plan) {
        return plan.timeExpression().startTime() != null
                && plan.timeExpression().endTime() != null;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(normalize(candidate))) return true;
        }
        return false;
    }

    private static void add(List<String> target, Object value) {
        String text = text(value);
        if (!text.isBlank()) target.add(text);
    }

    private static void addAll(List<String> target, Object raw) {
        if (raw instanceof Iterable<?> values) {
            values.forEach(value -> add(target, value));
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。、“”‘’：:；;？?（）()【】\\[\\]_-]+", "");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private record ScoredProfile(int score, Map<String, Object> profile) {
    }

    public enum AlignmentStatus {
        ALIGNED,
        MISMATCH,
        REVIEW_REQUIRED
    }

    public record AlignmentDecision(
            AlignmentStatus status,
            String failureCode,
            String reason,
            RequestPlan suggestedPlan,
            List<Map<String, Object>> candidates) {

        public AlignmentDecision {
            failureCode = failureCode == null ? "" : failureCode;
            reason = reason == null ? "" : reason;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static AlignmentDecision pass() {
            return new AlignmentDecision(
                    AlignmentStatus.ALIGNED, "", "", null, List.of());
        }

        public static AlignmentDecision mismatch(
                String code,
                String reason,
                RequestPlan suggestedPlan,
                List<Map<String, Object>> candidates) {
            return new AlignmentDecision(
                    AlignmentStatus.MISMATCH, code, reason, suggestedPlan, candidates);
        }

        public static AlignmentDecision review(
                String reason,
                List<Map<String, Object>> candidates) {
            return new AlignmentDecision(
                    AlignmentStatus.REVIEW_REQUIRED,
                    "TASK_TYPE_MISMATCH",
                    reason,
                    null,
                    candidates);
        }

        public boolean aligned() {
            return status == AlignmentStatus.ALIGNED;
        }
    }
}

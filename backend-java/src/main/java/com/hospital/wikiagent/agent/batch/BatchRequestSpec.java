package com.hospital.wikiagent.agent.batch;

import java.util.List;

/**
 * 描述一次请求是否应进入批量指标计算路径的确定性识别结果。
 *
 * <p>该对象只承载跨层传递所需的已知事实，不执行 I/O，也不在构造后改变运行状态。识别完全由
 * 确定性正则给出，不依赖小模型判断，避免 7B 模型在批量意图识别上不稳定。</p>
 */
public record BatchRequestSpec(
        Scope scope,
        String rawQuery,
        String timeText,
        List<Target> targets) {

    public BatchRequestSpec {
        scope = scope == null ? Scope.NONE : scope;
        rawQuery = rawQuery == null ? "" : rawQuery.strip();
        timeText = timeText == null || timeText.isBlank() ? rawQuery : timeText.strip();
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public boolean batch() {
        return scope != Scope.NONE;
    }

    public boolean allActive() {
        return scope == Scope.ALL_ACTIVE;
    }

    /** 非批量请求：继续走原有单指标/复合路径。 */
    public static BatchRequestSpec notBatch() {
        return new BatchRequestSpec(Scope.NONE, "", "", List.of());
    }

    /** 批量请求（计算全部活跃指标）。 */
    public static BatchRequestSpec allActive(String rawQuery) {
        return allActive(rawQuery, rawQuery);
    }

    public static BatchRequestSpec allActive(String rawQuery, String timeText) {
        return new BatchRequestSpec(Scope.ALL_ACTIVE, rawQuery, timeText, List.of());
    }

    /** 批量请求（只计算用户明确选择的指标）。 */
    public static BatchRequestSpec selected(
            String rawQuery, String timeText, List<Target> targets) {
        if (targets == null || targets.isEmpty()) {
            return notBatch();
        }
        return new BatchRequestSpec(Scope.SELECTED, rawQuery, timeText, targets);
    }

    public enum Scope {
        NONE,
        ALL_ACTIVE,
        SELECTED
    }

    public record Target(
            String ruleId,
            String ruleName,
            String profileId,
            String profileLabel) {
        public Target(String ruleId, String ruleName) {
            this(ruleId, ruleName, null, null);
        }

        public Target {
            ruleId = ruleId == null ? "" : ruleId.strip();
            ruleName = ruleName == null ? "" : ruleName.strip();
            profileId = profileId == null || profileId.isBlank()
                    ? null : profileId.strip();
            profileLabel = profileLabel == null || profileLabel.isBlank()
                    ? null : profileLabel.strip();
        }
    }
}

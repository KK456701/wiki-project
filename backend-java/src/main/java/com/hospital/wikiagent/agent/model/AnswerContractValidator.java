package com.hospital.wikiagent.agent.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.evidence.VerifiedEvidence;
import com.hospital.wikiagent.agent.model.AnswerTemplateRegistry.AnswerTemplate;

/**
 * 校验 Final Answer 是否遵守本轮选中的模板和 Evidence 数值契约。
 *
 * <p>这里不评价文风，也不尝试理解医学含义，只检查可以确定性验证的边界：禁止工具协议
 * 泄漏、禁止残留占位符、必需章节必须存在，试运行的分子、分母和指标率必须原样出现在
 * 回答中。校验失败时 FinalAnswerComposer 最多修复一次，随后使用确定性答案兜底。</p>
 */
@Component
public class AnswerContractValidator {
    public String validate(
            String content,
            AnswerTemplate template,
            List<VerifiedEvidence> evidence) {
        if (content == null || content.isBlank()) return "回答为空";
        String lower = content.toLowerCase();
        for (String forbidden : List.of(
                "tool_calls", "function call", "<｜｜dsml｜｜", "invoke name=")) {
            if (lower.contains(forbidden)) return "回答包含工具协议标记";
        }
        if (content.contains("{{") || content.contains("}}")) {
            return "回答仍包含未替换的模板占位符";
        }
        for (String section : template.requiredSections()) {
            if (!content.contains(section)) {
                return "回答缺少模板必需章节：" + section;
            }
        }
        if (template.preserveTrialNumbers()) {
            Map<String, Object> trial = latest(
                    evidence, List.of("caliber_trial_result", "trial_run"));
            for (String field : List.of(
                    "numerator_count", "denominator_count", "result_value")) {
                Object value = trial.get(field);
                if (value != null && !containsNumber(content, value)) {
                    return "回答未保留已验证数值：" + field + "=" + value;
                }
            }
        }
        String claimError = validateCaliberClaims(content, evidence);
        if (claimError != null) return claimError;
        return null;
    }

    /**
     * “没有发现差异证据”不等于“已经证明口径一致”。小模型容易把缺失证据改写成
     * 一致性结论，因此这里对高风险口径措辞做确定性门禁。
     */
    private static String validateCaliberClaims(
            String content,
            List<VerifiedEvidence> evidence) {
        Map<String, Object> rule = latest(evidence, List.of("effective_rule"));
        String effectiveLevel = String.valueOf(rule.getOrDefault("effective_level", ""));
        if (containsAny(content, List.of("当前采用国家口径", "当前按国家口径", "当前按国标口径"))
                && !"national".equalsIgnoreCase(effectiveLevel)) {
            return "回答把非国家层级规则错误表述为当前国家口径";
        }
        boolean explicitComparison = evidence.stream()
                .map(item -> item.evidence().safePayload())
                .anyMatch(payload -> payload.containsKey("comparison_status")
                        || payload.containsKey("comparison_metrics")
                        || payload.containsKey("difference_dimensions")
                        || payload.containsKey("caliber_candidates"));
        if (!explicitComparison && containsAny(content, List.of(
                "与国家口径一致", "与国标口径一致", "与国标一致",
                "无已证实差异", "不存在口径差异"))) {
            return "回答在缺少国标对比 Evidence 时声称口径一致或无差异";
        }
        return null;
    }

    private static boolean containsAny(String content, List<String> values) {
        return values.stream().anyMatch(content::contains);
    }

    private static Map<String, Object> latest(
            List<VerifiedEvidence> evidence,
            List<String> factTypes) {
        for (int index = evidence.size() - 1; index >= 0; index--) {
            var item = evidence.get(index).evidence();
            if (factTypes.contains(item.factType())) return item.safePayload();
        }
        return Map.of();
    }

    private static boolean containsNumber(String content, Object rawValue) {
        String value = String.valueOf(rawValue);
        if (containsStandaloneNumber(content, value)) return true;
        try {
            String normalized = new BigDecimal(value).stripTrailingZeros().toPlainString();
            return containsStandaloneNumber(content, normalized);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean containsStandaloneNumber(String content, String value) {
        return Pattern.compile("(?<![\\d.])" + Pattern.quote(value) + "(?![\\d.])")
                .matcher(content)
                .find();
    }
}

package com.hospital.wikiagent.contract;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Agent 无法安全继续时返回的结构化反问。
 *
 * <p>反问不是运行失败。服务端提供稳定选项编号和续接令牌，前端只负责展示和回传；
 * 后端校验选项后从原任务断点继续，仍需通过 Planner、IR、权限和 Evidence 链。</p>
 */
public record AgentClarification(
        String code,
        String kind,
        String title,
        String question,
        String helpText,
        String selectionMode,
        List<Option> options,
        boolean allowFreeText,
        String freeTextPlaceholder,
        String resumePrefix,
        String clarificationId,
        String field,
        String resumeToken) {

    public AgentClarification(
            String code,
            String kind,
            String title,
            String question,
            String helpText,
            String selectionMode,
            List<Option> options,
            boolean allowFreeText,
            String freeTextPlaceholder,
            String resumePrefix) {
        this(code, kind, title, question, helpText, selectionMode, options,
                allowFreeText, freeTextPlaceholder, resumePrefix, "", "", "");
    }

    public AgentClarification {
        code = safe(code);
        kind = safe(kind);
        title = safe(title);
        question = safe(question);
        helpText = safe(helpText);
        selectionMode = "multiple".equals(selectionMode) ? "multiple" : "single";
        options = options == null ? List.of() : List.copyOf(options);
        freeTextPlaceholder = safe(freeTextPlaceholder);
        resumePrefix = safe(resumePrefix);
        clarificationId = safe(clarificationId);
        if (clarificationId.isBlank()) {
            clarificationId = "CLR_" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 20);
        }
        field = safe(field);
        if (field.isBlank()) field = fieldFor(kind);
        resumeToken = safe(resumeToken);
        if (resumeToken.isBlank()) resumeToken = token(kind, resumePrefix, options);
    }

    /**
     * 一个可点击的澄清选项。value 是业务表达，不是 SQL、物理字段或任意工具参数。
     */
    public record Option(
            String id,
            String label,
            String value,
            String description,
            String group) {
        public Option {
            id = safe(id);
            label = safe(label);
            value = safe(value);
            description = safe(description);
            group = safe(group);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String fieldFor(String kind) {
        return switch (safe(kind)) {
            case "indicator_selection" -> "indicator";
            case "intent", "intent_selection" -> "intent";
            case "caliber_selection" -> "caliber";
            case "time_range" -> "time";
            default -> "free_text";
        };
    }

    private static String token(String kind, String resumePrefix, List<Option> options) {
        String values = options.stream()
                .map(option -> encode(option.id()) + "=" + encode(option.value()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        String payload = safe(kind) + "\n" + safe(resumePrefix) + "\n" + values;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(safe(value), StandardCharsets.UTF_8);
    }
}

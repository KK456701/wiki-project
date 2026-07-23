package com.hospital.wikiagent.contract;

import java.util.List;

/**
 * Agent 无法安全继续时返回的结构化反问。
 *
 * <p>反问不是运行失败。服务端只提供允许选择的业务值和用于恢复原任务的文字前缀，
 * 前端负责展示按钮、搜索和多选交互。用户的选择仍会作为下一轮自然语言请求重新进入
 * Planner、IR、权限和 Evidence 链，不能借此绕过任何服务端校验。</p>
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
        String resumePrefix) {

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
}

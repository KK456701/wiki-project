package com.hospital.wikiagent.agent.batch;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hospital.wikiagent.agent.batch.BatchRequestSpec.Target;
import com.hospital.wikiagent.agent.memory.AgentConversationMemory.QueryScopeState;
import com.hospital.wikiagent.agent.model.AgentModelInvoker;
import com.hospital.wikiagent.agent.model.AgentModelProperties;
import com.hospital.wikiagent.agent.model.AgentModelRegistry;
import com.hospital.wikiagent.agent.model.ModelJsonExtractor;
import com.hospital.wikiagent.agent.model.PromptCatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批量路径的 LLM 意图兜底校验：只在正则结果与会话状态存在歧义时调用模型，
 * 超时或失败时安全回退到正则结果，不阻断主链路。
 *
 * <p>触发条件（满足任一即调用 LLM）：
 * <ol>
 *   <li>正则判定 ALL_ACTIVE，但会话里记着 SINGLE/SUBSET 的具体指标；</li>
 *   <li>正则未命中批量，但查询包含"指标"+"计算/结果"等批量信号词。</li>
 * </ol>
 * 模型只输出 {@code {"scope":"ALL"|"SELECTED","confidence":0.0~1.0}}，
 * 置信度低于 0.6 时不做覆盖，保留正则结果（后续可触发澄清）。</p>
 */
@Component
public class BatchIntentVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(BatchIntentVerifier.class);
    private static final double MIN_CONFIDENCE = 0.6;

    private final AgentModelInvoker models;
    private final AgentModelRegistry registry;
    private final AgentModelProperties properties;
    private final PromptCatalog prompts;
    private final ObjectMapper objectMapper;

    public BatchIntentVerifier(
            AgentModelInvoker models,
            AgentModelRegistry registry,
            AgentModelProperties properties,
            PromptCatalog prompts,
            ObjectMapper objectMapper) {
        this.models = models;
        this.registry = registry;
        this.properties = properties;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验正则检测结果。只在歧义场景调用 LLM；无歧义或 LLM 不可用时原样返回。
     *
     * @param spec     正则检测器的结果
     * @param previous 会话中记住的上一轮查询范围（可为 null）
     * @param query    用户原始输入
     * @return 校验后的 spec（可能与输入相同）
     */
    public BatchRequestSpec verify(
            BatchRequestSpec spec, QueryScopeState previous, String query) {
        if (!needsVerification(spec, previous)) {
            return spec;
        }
        try {
            String modelId = registry.defaultModelId();
            String userPrompt = buildUserPrompt(spec, previous, query);
            String raw = models.complete(
                    modelId,
                    prompts.batchIntentVerifier(),
                    userPrompt,
                    properties.getBatchVerifyTimeout()).content();
            JsonNode node = objectMapper.readTree(ModelJsonExtractor.firstObject(raw));
            String scope = node.has("scope") ? node.get("scope").asText("") : "";
            double confidence = node.has("confidence")
                    ? node.get("confidence").asDouble(0.0) : 0.0;
            if (confidence < MIN_CONFIDENCE) {
                LOGGER.info("Batch intent LLM confidence {} below threshold; keeping regex result",
                        confidence);
                return spec;
            }
            return applyVerdict(spec, previous, scope);
        } catch (Exception exception) {
            LOGGER.warn("Batch intent LLM verification failed; falling back to regex result: {}",
                    exception.getMessage());
            return spec;
        }
    }

    /**
     * 判断是否需要 LLM 校验：正则 ALL 但会话有具体指标，或正则未命中但有批量信号。
     */
    static boolean needsVerification(BatchRequestSpec spec, QueryScopeState previous) {
        // 情况 1：正则说全部，但会话记着具体指标
        if (spec.allActive() && previous != null && previous.valid()
                && ("SINGLE".equals(previous.targetMode())
                        || "SUBSET".equals(previous.targetMode()))
                && !previous.targets().isEmpty()) {
            return true;
        }
        // 情况 2：正则未命中批量，但查询像批量请求（含"指标"+"计算/结果"）
        if (!spec.batch() && looksLikeBatch(query(spec))) {
            return true;
        }
        return false;
    }

    private static boolean looksLikeBatch(String query) {
        if (query == null || query.isBlank()) return false;
        String compact = query.replaceAll("\\s+", "");
        boolean hasIndicator = compact.contains("指标");
        boolean hasAction = compact.contains("计算") || compact.contains("结果")
                || compact.contains("算一下") || compact.contains("统计");
        return hasIndicator && hasAction;
    }

    private static String query(BatchRequestSpec spec) {
        return spec.rawQuery();
    }

    private BatchRequestSpec applyVerdict(
            BatchRequestSpec spec, QueryScopeState previous, String scope) {
        if ("SELECTED".equals(scope) && spec.allActive()
                && previous != null && !previous.targets().isEmpty()) {
            // LLM 认为是选定指标：用会话记住的目标覆盖全量
            List<Target> remembered = previous.targets().stream()
                    .map(value -> new Target(value.ruleId(), value.ruleName()))
                    .toList();
            return BatchRequestSpec.selected(spec.rawQuery(), spec.timeText(), remembered);
        }
        if ("ALL".equals(scope) && !spec.batch()) {
            // LLM 认为是全量但正则没命中：升级为全量
            return BatchRequestSpec.allActive(spec.rawQuery(), spec.timeText());
        }
        return spec;
    }

    private String buildUserPrompt(
            BatchRequestSpec spec, QueryScopeState previous, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户当前输入：\n").append(query == null ? "" : query).append("\n\n");
        sb.append("正则检测结果：scope=").append(spec.scope().name());
        if (!spec.targets().isEmpty()) {
            sb.append("，点名指标=").append(spec.targets().stream()
                    .map(Target::ruleName).collect(Collectors.joining("、")));
        }
        sb.append("\n\n");
        if (previous != null && previous.valid()) {
            sb.append("会话记住的上一轮范围：targetMode=").append(previous.targetMode());
            if (!previous.targets().isEmpty()) {
                sb.append("，指标=").append(previous.targets().stream()
                        .map(value -> value.ruleName())
                        .collect(Collectors.joining("、")));
            }
            sb.append("\n");
        } else {
            sb.append("会话无历史范围记录。\n");
        }
        return sb.toString();
    }
}

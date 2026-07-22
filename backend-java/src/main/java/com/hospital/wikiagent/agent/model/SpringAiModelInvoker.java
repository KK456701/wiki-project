package com.hospital.wikiagent.agent.model;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Spring AI 模型适配器：统一调用 Ollama 与 OpenAI 兼容 API，并返回最小化文本结果。
 *
 * <p>本地 Ollama 强制单并发，外部 API 最多双并发，防止 8B 模型或远程配额被复合任务压垮。
 * 超时由 CompletableFuture 在调用边界统一处理；本类不注册 Spring AI 自动工具调用，
 * Planner 和 Final Answer 始终只能生成文本。</p>
 */
@Component
public class SpringAiModelInvoker implements AgentModelInvoker {
    public static final String VERSION = "spring-ai-model-adapter-v1";
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiModelInvoker.class);
    private final AgentModelRegistry registry;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Semaphore ollama = new Semaphore(1);
    private final Semaphore api = new Semaphore(2);

    public SpringAiModelInvoker(AgentModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 在指定超时内完成一次纯文本模型调用，并把底层异常归一化为安全错误。
     */
    @Override
    public ModelCompletion complete(
            String modelId,
            String systemPrompt,
            String userPrompt,
            Duration timeout) {
        String resolvedId = modelId == null || modelId.isBlank() ? registry.defaultModelId() : modelId;
        AgentModelInfo info = registry.requireInfo(resolvedId);
        Semaphore semaphore = "ollama".equals(info.provider()) ? ollama : api;
        try {
            return CompletableFuture.supplyAsync(
                            () -> invoke(resolvedId, systemPrompt, userPrompt, semaphore), executor)
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            LOGGER.warn("Agent model call failed for model {}: {}", resolvedId, cause.toString(), cause);
            throw new AgentModelUnavailableException(
                    "MODEL_CALL_FAILED", "模型调用失败：" + cause.getClass().getSimpleName());
        }
    }

    private ModelCompletion invoke(
            String modelId,
            String systemPrompt,
            String userPrompt,
            Semaphore semaphore) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            var response = registry.requireModel(modelId).call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt))));
            String content = response.getResult().getOutput().getText();
            return new ModelCompletion(modelId, content);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentModelUnavailableException("MODEL_CALL_INTERRUPTED", "模型调用被中断。");
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}

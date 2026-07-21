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
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class SpringAiModelInvoker implements AgentModelInvoker {
    public static final String VERSION = "spring-ai-model-adapter-v1";
    private final AgentModelRegistry registry;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Semaphore ollama = new Semaphore(1);
    private final Semaphore api = new Semaphore(2);

    public SpringAiModelInvoker(AgentModelRegistry registry) {
        this.registry = registry;
    }

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

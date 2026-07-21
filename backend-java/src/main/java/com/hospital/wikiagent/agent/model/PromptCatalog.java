package com.hospital.wikiagent.agent.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptCatalog {
    public static final String VERSION = "java-agent-prompts-v1";

    public String planner() { return read("prompts/planner-system.txt"); }
    public String plannerRepair() { return read("prompts/planner-repair.txt"); }
    public String finalAnswer() { return read("prompts/final-answer-system.txt"); }
    public String finalAnswerCorrection() { return read("prompts/final-answer-correction.txt"); }

    private String read(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取提示词: " + path, exception);
        }
    }
}

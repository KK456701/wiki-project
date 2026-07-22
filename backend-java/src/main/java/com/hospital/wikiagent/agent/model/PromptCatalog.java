package com.hospital.wikiagent.agent.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 实现 {@code PromptCatalog} 对应的领域职责。
 *
 * <p>该类型在所属包边界内完成单一领域职责，并通过构造器显式接收依赖。涉及外部 I/O、权限或患者数据时，必须复用现有网关和安全对象，不能在此处建立旁路。</p>
 */
@Component
public class PromptCatalog {
    public static final String VERSION = "java-agent-prompts-v5";

    public String planner() { return read("prompts/planner-system.txt"); }
    public String plannerRepair() { return read("prompts/planner-repair.txt"); }
    public String replanner() { return read("prompts/replanner-instruction.txt"); }
    public String finalAnswer() { return read("prompts/final-answer-system.txt"); }
    public String finalAnswerCorrection() { return read("prompts/final-answer-correction.txt"); }
    public String indicatorCandidateDisambiguator() {
        return read("prompts/indicator-candidate-disambiguator.txt");
    }

    private String read(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取提示词: " + path, exception);
        }
    }
}

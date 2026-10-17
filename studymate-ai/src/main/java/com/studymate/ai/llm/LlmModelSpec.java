package com.studymate.ai.llm;

import lombok.Data;

import java.util.List;

/** 一条"框架 × 档位 × 能力位"的模型配置 */
@Data
public class LlmModelSpec {
    private String name;
    private LlmFramework framework = LlmFramework.SPRING_AI;
    private LlmTier tier = LlmTier.BALANCED;
    private List<LlmCapability> capabilities = List.of(LlmCapability.CHAT);
    private double temperature = 0.7;
    private int maxTokens = 4096;
    private int timeoutMs = 60_000;
    /** 推理强度：minimal/low/medium/high，null 表示使用模型默认 */
    private String reasoningEffort;
    private int embeddingDimensions = 1024;
    private boolean defaultModel = false;

    public boolean supports(LlmCapability capability) {
        return capabilities != null && capabilities.contains(capability);
    }
}

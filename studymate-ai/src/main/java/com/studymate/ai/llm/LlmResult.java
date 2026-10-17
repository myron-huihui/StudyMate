package com.studymate.ai.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmResult {
    /** 可进入上下文的正式回答（已剔除思维链） */
    private String content;
    /** 思维链原文：只做计量与审计，不写回上下文 */
    private String thinking;
    private int promptTokens;
    private int completionTokens;
    private int thinkingTokens;
    private String model;
    private LlmFramework framework;
    private LlmTier tier;
    private long latencyMs;
    private int attempts;
    private boolean degraded;

    public int totalTokens() {
        return promptTokens + completionTokens + thinkingTokens;
    }
}

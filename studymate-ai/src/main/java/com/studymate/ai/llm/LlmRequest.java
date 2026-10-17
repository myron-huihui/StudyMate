package com.studymate.ai.llm;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class LlmRequest {
    private String systemPrompt;
    private String userPrompt;
    @Builder.Default
    private LlmTier tier = LlmTier.BALANCED;
    @Builder.Default
    private LlmCapability capability = LlmCapability.CHAT;
    /** 指定模型时忽略档位路由 */
    private String modelName;
    private Integer timeoutMs;
    private Integer maxRetries;
    /** 最多尝试的候选模型数（默认全部） */
    private Integer maxCandidates;
    private Double temperature;
    private Integer maxTokens;
    private String memoryId;
    private Long userId;
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public Map<String, Object> metadataOrEmpty() {
        return metadata != null ? metadata : Map.of();
    }
}

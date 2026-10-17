package com.studymate.ai.llm;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 模型注册表：按 "能力位 × 档位" 解析候选模型，支持框架混布与降级切换。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "ai.gateway")
public class LlmModelRegistry {

    private boolean enabled = true;
    private int maxRetries = 2;
    private int timeoutMs = 60_000;
    private long backoffBaseMs = 500;
    private long backoffMaxMs = 5_000;
    private List<LlmModelSpec> models = new ArrayList<>();

    /** 解析候选：精确档位优先，其次同能力其他档位，最后默认模型 */
    public List<LlmModelSpec> candidates(LlmCapability capability, LlmTier tier) {
        List<LlmModelSpec> matched = new ArrayList<>(models.stream()
                .filter(m -> m.supports(capability))
                .sorted(Comparator.comparingInt(m -> tierDistance(m.getTier(), tier)))
                .toList());
        if (matched.isEmpty()) {
            LlmModelSpec fallback = new LlmModelSpec();
            fallback.setName("deepseek-v4.1-flash");
            fallback.setFramework(LlmFramework.SPRING_AI);
            fallback.setTier(tier);
            fallback.setCapabilities(List.of(capability));
            matched.add(fallback);
        }
        return matched;
    }

    public Optional<LlmModelSpec> byName(String name) {
        return models.stream().filter(m -> m.getName().equals(name)).findFirst();
    }

    public LlmModelSpec first(LlmCapability capability, LlmTier tier) {
        return candidates(capability, tier).get(0);
    }

    private int tierDistance(LlmTier actual, LlmTier wanted) {
        return Math.abs(actual.ordinal() - wanted.ordinal());
    }
}

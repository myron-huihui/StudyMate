package com.studymate.ai.llm;

import com.studymate.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 统一模型调用门面。
 * 业务层只依赖本类，模型框架/实现细节在 ai.llm 包内闭环。
 */
@Slf4j
@Service
public class LlmGateway {

    private final LlmModelRegistry registry;
    private final Map<LlmFramework, LlmClient> clients = new EnumMap<>(LlmFramework.class);
    private final LlmMetrics metrics;
    private final LangfuseReporter langfuseReporter;
    private final ChatModel streamModel;
    private final AiProperties aiProperties;

    public LlmGateway(LlmModelRegistry registry, List<LlmClient> clientList,
                      LlmMetrics metrics, LangfuseReporter langfuseReporter,
                      ChatModel streamModel, AiProperties aiProperties) {
        this.registry = registry;
        this.metrics = metrics;
        this.langfuseReporter = langfuseReporter;
        this.streamModel = streamModel;
        this.aiProperties = aiProperties;
        clientList.forEach(c -> clients.put(c.framework(), c));
    }

    /** 便捷调用：默认 balanced 档位 */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, LlmTier.BALANCED);
    }

    public String chat(String systemPrompt, String userPrompt, LlmTier tier) {
        LlmResult result = call(LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .tier(tier)
                .capability(LlmCapability.CHAT)
                .build());
        return result.getContent();
    }

    public String chat(LlmTier tier, LlmCapability capability, String systemPrompt, String userPrompt) {
        return call(LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .tier(tier)
                .capability(capability)
                .build()).getContent();
    }

    /** 带输出上限、超时与候选数的受控调用（供工作流节点控制总时延） */
    public String chat(String systemPrompt, String userPrompt, LlmTier tier,
                       Integer maxTokens, Integer timeoutMs, Integer maxCandidates) {
        return call(LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .tier(tier)
                .capability(LlmCapability.CHAT)
                .maxTokens(maxTokens)
                .timeoutMs(timeoutMs)
                .maxCandidates(maxCandidates)
                .build()).getContent();
    }

    /** 核心调用：按能力位 × 档位解析候选，失败自动切换框架/模型 */
    public LlmResult call(LlmRequest request) {
        List<LlmModelSpec> candidates = request.getModelName() != null
                ? registry.byName(request.getModelName()).map(List::of).orElse(registry.candidates(request.getCapability(), request.getTier()))
                : registry.candidates(request.getCapability(), request.getTier());

        LlmException last = null;
        boolean degraded = false;
        LlmModelSpec previous = null;

        int limit = request.getMaxCandidates() != null && request.getMaxCandidates() > 0
                ? Math.min(request.getMaxCandidates(), candidates.size())
                : candidates.size();
        for (int i = 0; i < limit; i++) {
            LlmModelSpec spec = candidates.get(i);
            LlmClient client = clients.get(spec.getFramework());
            if (client == null) {
                continue;
            }
            if (previous != null) {
                metrics.recordFailover(previous, spec);
            }
            try {
                LlmResult result = client.chat(spec, request);
                result.setDegraded(degraded);
                langfuseReporter.reportGeneration(request, result);
                return result;
            } catch (LlmException e) {
                log.warn("Model {} ({}) failed: {}", spec.getName(), spec.getFramework(), e.getMessage());
                last = e;
                degraded = true;
                previous = spec;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new LlmException("没有可用的模型配置", null, 0, false);
    }

    /** 流式：优先 Spring AI，LangChain4j 作为非流式降级由调用方决定 */
    public Flux<String> stream(String systemPrompt, String userPrompt) {
        LlmModelSpec spec = registry.candidates(LlmCapability.CHAT, LlmTier.BALANCED).stream()
                .filter(s -> s.getFramework() == LlmFramework.SPRING_AI)
                .findFirst()
                .orElseGet(() -> {
                    LlmModelSpec fallback = new LlmModelSpec();
                    fallback.setName(aiProperties.getModel());
                    return fallback;
                });
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(spec.getName())
                .temperature(spec.getTemperature())
                .maxTokens(spec.getMaxTokens())
                .build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)), options);
        return streamModel.stream(prompt)
                .mapNotNull(response -> response.getResult() != null
                        ? response.getResult().getOutput().getText()
                        : null)
                .filter(delta -> !delta.isEmpty());
    }

    public float[] embed(String text) {
        LlmModelSpec spec = registry.first(LlmCapability.EMBEDDING, LlmTier.BALANCED);
        LlmClient client = clients.get(spec.getFramework());
        if (client == null) {
            throw new LlmException("没有可用的 Embedding 模型", null, 0, false);
        }
        return client.embed(spec, text);
    }

    public float[] embed(String text, LlmTier tier) {
        LlmModelSpec spec = registry.first(LlmCapability.EMBEDDING, tier);
        LlmClient client = clients.get(spec.getFramework());
        if (client == null) {
            throw new LlmException("没有可用的 Embedding 模型", null, 0, false);
        }
        return client.embed(spec, text);
    }
}

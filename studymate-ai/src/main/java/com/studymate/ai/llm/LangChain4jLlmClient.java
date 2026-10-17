package com.studymate.ai.llm;

import com.studymate.ai.config.AiProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 策略二：LangChain4j（同一 OpenAI 兼容端点，独立 SDK 生命周期） */
@Slf4j
@Component
public class LangChain4jLlmClient extends AbstractLlmClient {

    private final AiProperties aiProperties;
    private final Map<String, OpenAiChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, OpenAiEmbeddingModel> embeddingModels = new ConcurrentHashMap<>();

    public LangChain4jLlmClient(LlmMetrics metrics, LlmModelRegistry registry, AiProperties aiProperties) {
        super(metrics, registry);
        this.aiProperties = aiProperties;
    }

    @Override
    public LlmFramework framework() {
        return LlmFramework.LANGCHAIN4J;
    }

    @Override
    protected LlmResult doCall(LlmModelSpec spec, LlmRequest request) {
        OpenAiChatModel model = chatModels.computeIfAbsent(spec.getName(), name -> {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                    .baseUrl(aiProperties.getBailian().getBaseUrl())
                    .apiKey(aiProperties.getBailian().getApiKey())
                    .modelName(name)
                    .temperature(request.getTemperature() != null ? request.getTemperature() : spec.getTemperature())
                    .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : spec.getMaxTokens())
                    .timeout(Duration.ofMillis(spec.getTimeoutMs()))
                    .maxRetries(0);
            if (spec.getReasoningEffort() != null && !spec.getReasoningEffort().isBlank()) {
                builder.reasoningEffort(spec.getReasoningEffort());
            }
            return builder.build();
        });

        List<ChatMessage> messages = new ArrayList<>(2);
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(SystemMessage.from(request.getSystemPrompt()));
        }
        messages.add(UserMessage.from(request.getUserPrompt()));

        ChatResponse response = model.chat(messages);
        String raw = response.aiMessage() != null ? response.aiMessage().text() : "";
        ThinkingExtractor.Split split = ThinkingExtractor.split(raw);

        TokenUsage usage = response.tokenUsage();
        int promptTokens = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int completionTokens = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;

        return LlmResult.builder()
                .content(split.content())
                .thinking(split.thinking())
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .thinkingTokens(ThinkingExtractor.estimateTokens(split.thinking()))
                .build();
    }

    @Override
    protected float[] doEmbed(LlmModelSpec spec, String text) {
        OpenAiEmbeddingModel model = embeddingModels.computeIfAbsent(spec.getName(), name -> OpenAiEmbeddingModel.builder()
                .baseUrl(aiProperties.getBailian().getBaseUrl())
                .apiKey(aiProperties.getBailian().getApiKey())
                .modelName(name)
                .dimensions(spec.getEmbeddingDimensions())
                .timeout(Duration.ofMillis(spec.getTimeoutMs()))
                .maxRetries(0)
                .build());
        return model.embed(text).content().vector();
    }
}

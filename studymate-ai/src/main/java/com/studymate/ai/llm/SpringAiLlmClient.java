package com.studymate.ai.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 策略一：Spring AI（OpenAI 兼容协议） */
@Slf4j
@Component
public class SpringAiLlmClient extends AbstractLlmClient {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    public SpringAiLlmClient(LlmMetrics metrics, LlmModelRegistry registry,
                             ChatModel chatModel, EmbeddingModel embeddingModel) {
        super(metrics, registry);
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public LlmFramework framework() {
        return LlmFramework.SPRING_AI;
    }

    @Override
    protected LlmResult doCall(LlmModelSpec spec, LlmRequest request) {
        List<Message> messages = new ArrayList<>(2);
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.getSystemPrompt()));
        }
        messages.add(new UserMessage(request.getUserPrompt()));

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(spec.getName())
                .temperature(request.getTemperature() != null ? request.getTemperature() : spec.getTemperature())
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : spec.getMaxTokens());
        if (spec.getReasoningEffort() != null && !spec.getReasoningEffort().isBlank()) {
            optionsBuilder.reasoningEffort(spec.getReasoningEffort());
        }
        OpenAiChatOptions options = optionsBuilder.build();

        ChatResponse response = chatModel.call(new Prompt(messages, options));
        String raw = response.getResult().getOutput().getText();
        ThinkingExtractor.Split split = ThinkingExtractor.split(raw);

        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        int promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;

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
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(spec.getName())
                .dimensions(spec.getEmbeddingDimensions())
                .build();
        EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of(text), options));
        return response.getResults().get(0).getOutput();
    }
}

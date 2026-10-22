package com.studymate.ai.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.ai.config.AiProperties;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmTier;
import com.studymate.ai.rag.RagHybridSearchService;
import com.studymate.common.dto.MaterialRecommendResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 资料推荐服务：RAG 检索与 LLM 生成分离。
 * 检索在独立有界线程池执行，单路超时直接丢弃上下文继续生成；队列满降级为无上下文，绝不抛给调用方。
 */
@Slf4j
@Service
public class MaterialRecommendService {

    private final RagHybridSearchService ragService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final ThreadPoolExecutor retrievalPool;

    private static final String SYSTEM_PROMPT = """
            你是学习资料推荐引擎。根据用户的学习目标和当前任务，从检索结果中筛选最相关资料，
            并生成推荐理由。输出严格 JSON 数组：
            [
              {
                "material_id": int,
                "title": "资料标题",
                "category": "类别",
                "reason": "推荐理由（1-2句话，关联到当前学习目标）",
                "score": 0.0-1.0
              }
            ]
            只推荐检索结果中确实存在的资料，不要编造。若没有检索结果，返回空数组 []。
            """;

    public MaterialRecommendService(RagHybridSearchService ragService, LlmGateway llmGateway,
                                    ObjectMapper objectMapper, AiProperties aiProperties) {
        this.ragService = ragService;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        AtomicInteger counter = new AtomicInteger();
        this.retrievalPool = new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "recommend-retrieval-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 为指定学习目标推荐资料
     */
    public List<MaterialRecommendResponse> recommend(String goal, String currentTask, int topK) {
        String query = goal + " " + (currentTask == null ? "" : currentTask);
        String context = retrieveContext(query, topK);
        String contextBlock = context.isBlank()
                ? "（检索结果为空或检索超时，请仅基于目标给出通用建议，不要编造资料条目）"
                : context;

        String userPrompt = String.format("""
                学习目标：%s
                当前任务：%s
                检索到的知识片段：
                %s
                
                请从以上资料中推荐 top%d 条，并生成推荐理由。
                """, goal, currentTask, contextBlock, topK);
        String raw = llmGateway.chat(SYSTEM_PROMPT, userPrompt, LlmTier.BALANCED);
        log.debug("Material recommend raw: {}", raw);
        return parseResults(raw);
    }

    /** 单路检索：超时丢弃、失败降级、队列满降级，均返回空上下文 */
    private String retrieveContext(String query, int topK) {
        long timeoutMs = aiProperties.getRag().getRouteTimeoutMs();
        try {
            return CompletableFuture
                    .supplyAsync(() -> ragService.buildContext(query, topK), retrievalPool)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(e -> {
                        log.warn("Material retrieval degraded ({}ms budget): {}", timeoutMs, rootMessage(e));
                        return "";
                    })
                    .join();
        } catch (RejectedExecutionException e) {
            log.warn("Material retrieval pool saturated, degrade without context");
            return "";
        } catch (Exception e) {
            log.warn("Material retrieval failed, degrade without context: {}", rootMessage(e));
            return "";
        }
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    @PreDestroy
    void shutdown() {
        retrievalPool.shutdownNow();
    }

    private List<MaterialRecommendResponse> parseResults(String raw) {
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, MaterialRecommendResponse.class);
            return objectMapper.readValue(extractJsonArray(raw), type);
        } catch (Exception e) {
            log.warn("Failed to parse recommend results: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonArray(String raw) {
        int start = raw.indexOf("[");
        int end = raw.lastIndexOf("]");
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "[]";
    }
}

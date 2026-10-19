package com.studymate.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.ai.config.AiProperties;
import com.studymate.ai.llm.LlmMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gte-rerank 远端精排（第一级）。
 * 连续失败达阈值后熔断一段时间，直接走本地重排，避免拖慢主链路。
 */
@Slf4j
@Component
public class GteRerankClient {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final LlmMetrics metrics;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .build();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntil = 0L;

    public GteRerankClient(AiProperties aiProperties, ObjectMapper objectMapper, LlmMetrics metrics) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public boolean available() {
        return aiProperties.getBailian().getApiKey() != null
                && !aiProperties.getBailian().getApiKey().isBlank()
                && System.currentTimeMillis() >= openUntil;
    }

    public record Score(int index, double score) {
    }

    public List<Score> rerank(String query, List<String> documents, int topN) {
        AiProperties.Rag rag = aiProperties.getRag();
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", documents);
            Map<String, Object> parameters = Map.of("return_documents", false, "top_n", topN);
            Map<String, Object> payload = Map.of(
                    "model", rag.getRerankModel(),
                    "input", input,
                    "parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder(URI.create(rag.getRerankEndpoint()))
                    .timeout(Duration.ofMillis(rag.getRerankTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiProperties.getBailian().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("rerank http " + response.statusCode());
            }
            JsonNode results = objectMapper.readTree(response.body()).path("output").path("results");
            List<Score> scores = new ArrayList<>();
            for (JsonNode node : results) {
                scores.add(new Score(node.path("index").asInt(), node.path("relevance_score").asDouble()));
            }
            consecutiveFailures.set(0);
            return scores;
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= rag.getRerankFailureThreshold()) {
                openUntil = System.currentTimeMillis() + rag.getRerankOpenMs();
                consecutiveFailures.set(0);
                log.warn("gte-rerank circuit opened for {}ms: {}", rag.getRerankOpenMs(), e.getMessage());
            }
            metrics.recordDegrade("rerank", "LOCAL");
            log.warn("gte-rerank failed, degrade to local rerank: {}", e.getMessage());
            return null;
        }
    }
}

package com.studymate.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.common.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Langfuse 全量上报：异步、失败静默，不影响主链路。
 */
@Slf4j
@Component
public class LangfuseReporter {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${ai.langfuse.enabled:false}")
    private boolean enabled;

    @Value("${ai.langfuse.host:https://cloud.langfuse.com}")
    private String host;

    @Value("${ai.langfuse.public-key:}")
    private String publicKey;

    @Value("${ai.langfuse.secret-key:}")
    private String secretKey;

    public LangfuseReporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void reportGeneration(LlmRequest request, LlmResult result) {
        if (!enabled) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", UUID.randomUUID().toString());
        body.put("traceId", TraceContext.current());
        body.put("name", "llm.chat");
        body.put("model", result.getModel());
        body.put("input", truncate(request.getUserPrompt(), 2000));
        body.put("output", truncate(result.getContent(), 2000));
        body.put("usage", Map.of(
                "input", result.getPromptTokens(),
                "output", result.getCompletionTokens(),
                "total", result.totalTokens()));
        body.put("metadata", Map.of(
                "framework", String.valueOf(result.getFramework()),
                "tier", String.valueOf(result.getTier()),
                "capability", String.valueOf(request.getCapability()),
                "latencyMs", result.getLatencyMs(),
                "degraded", result.isDegraded()));
        body.put("level", "DEFAULT");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", "generation-create");
        event.put("timestamp", java.time.Instant.now().toString());
        event.put("body", body);

        send(Map.of("batch", List.of(event)));
    }

    private void send(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            String auth = Base64.getEncoder()
                    .encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/api/public/ingestion"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + auth)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(resp -> {
                        if (resp.statusCode() >= 300) {
                            log.debug("Langfuse ingestion failed: {}", resp.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        log.debug("Langfuse ingestion error: {}", e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.debug("Langfuse payload error: {}", e.getMessage());
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}

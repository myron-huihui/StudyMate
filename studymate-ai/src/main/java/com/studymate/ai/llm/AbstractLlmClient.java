package com.studymate.ai.llm;

import com.studymate.common.trace.TraceContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 模板方法：所有框架共享重试 / 超时 / 429 退避 / token 计量的统一语义。
 */
@Slf4j
public abstract class AbstractLlmClient implements LlmClient {

    private final LlmMetrics metrics;
    private final LlmModelRegistry registry;
    private final ExecutorService ioExecutor = new ThreadPoolExecutor(
            4, 16, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            new ThreadPoolExecutor.CallerRunsPolicy());

    protected AbstractLlmClient(LlmMetrics metrics, LlmModelRegistry registry) {
        this.metrics = metrics;
        this.registry = registry;
    }

    protected abstract LlmResult doCall(LlmModelSpec spec, LlmRequest request);

    protected abstract float[] doEmbed(LlmModelSpec spec, String text);

    @Override
    public LlmResult chat(LlmModelSpec spec, LlmRequest request) {
        int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : registry.getMaxRetries();
        int timeoutMs = request.getTimeoutMs() != null ? request.getTimeoutMs() : spec.getTimeoutMs();
        long started = System.currentTimeMillis();
        LlmException last = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                LlmResult result = CompletableFuture
                        .supplyAsync(TraceContext.wrap(() -> {
                            TraceContext.enterSpan(framework() + "-llm");
                            try {
                                return doCall(spec, request);
                            } finally {
                                TraceContext.exitSpan();
                            }
                        }), ioExecutor)
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
                result.setLatencyMs(System.currentTimeMillis() - started);
                result.setAttempts(attempt);
                result.setModel(spec.getName());
                result.setFramework(framework());
                result.setTier(spec.getTier());
                metrics.recordSuccess(result);
                return result;
            } catch (TimeoutException e) {
                last = new LlmException("LLM timeout after " + timeoutMs + "ms", e, 0, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("LLM call interrupted", e, 0, false);
            } catch (ExecutionException e) {
                last = classify(e.getCause());
            }

            if (attempt <= maxRetries && last.isRetryable()) {
                long delay = ThinkingExtractor.backoffMillis(attempt, registry.getBackoffBaseMs(),
                        registry.getBackoffMaxMs());
                log.warn("LLM attempt {}/{} failed on {}: {}; retry in {}ms",
                        attempt, maxRetries + 1, spec.getName(), last.getMessage(), delay);
                sleep(delay);
            } else {
                break;
            }
        }

        metrics.recordFailure(spec, request.getCapability(), last);
        throw last;
    }

    @Override
    public float[] embed(LlmModelSpec spec, String text) {
        try {
            return CompletableFuture.supplyAsync(TraceContext.wrap(() -> doEmbed(spec, text)), ioExecutor)
                    .get(spec.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new LlmException("Embedding timeout", e, 0, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Embedding interrupted", e, 0, false);
        } catch (ExecutionException e) {
            throw classify(e.getCause());
        }
    }

    private LlmException classify(Throwable error) {
        if (error instanceof LlmException llmException) {
            return llmException;
        }
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        int status = 0;
        if (message.contains("429") || message.toLowerCase().contains("rate limit")) {
            status = 429;
        } else if (message.contains("401") || message.contains("403")) {
            status = 401;
        } else if (message.contains("500") || message.contains("502")
                || message.contains("503") || message.contains("504")) {
            status = 500;
        } else if (message.toLowerCase().contains("timeout") || message.toLowerCase().contains("timed out")) {
            status = 408;
        }
        boolean retryable = status == 429 || status == 408 || status >= 500;
        return new LlmException("LLM call failed: " + message, error, status, retryable);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM retry interrupted", e, 0, false);
        }
    }

    @PreDestroy
    void shutdown() {
        ioExecutor.shutdownNow();
    }
}

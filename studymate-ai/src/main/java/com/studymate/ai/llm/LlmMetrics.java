package com.studymate.ai.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** LLM 调用指标：次数 / 耗时 / token 计量 */
@Component
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(LlmResult result) {
        Timer.builder("llm_call_duration")
                .tag("model", safe(result.getModel()))
                .tag("framework", String.valueOf(result.getFramework()))
                .tag("tier", String.valueOf(result.getTier()))
                .tag("outcome", "success")
                .register(registry)
                .record(result.getLatencyMs(), TimeUnit.MILLISECONDS);
        Counter.builder("llm_calls_total")
                .tag("model", safe(result.getModel()))
                .tag("framework", String.valueOf(result.getFramework()))
                .tag("outcome", "success")
                .register(registry)
                .increment();
        token("prompt", result.getModel(), result.getPromptTokens());
        token("completion", result.getModel(), result.getCompletionTokens());
        token("thinking", result.getModel(), result.getThinkingTokens());
    }

    public void recordFailure(LlmModelSpec spec, LlmCapability capability, Throwable error) {
        Counter.builder("llm_calls_total")
                .tag("model", safe(spec != null ? spec.getName() : "unknown"))
                .tag("framework", spec != null ? String.valueOf(spec.getFramework()) : "unknown")
                .tag("outcome", "failure")
                .tag("capability", String.valueOf(capability))
                .register(registry)
                .increment();
    }

    public void recordFailover(LlmModelSpec from, LlmModelSpec to) {
        Counter.builder("llm_failover_total")
                .tag("from", safe(from != null ? from.getName() : "unknown"))
                .tag("to", safe(to != null ? to.getName() : "unknown"))
                .register(registry)
                .increment();
    }

    public void recordDegrade(String component, String level) {
        Counter.builder("degrade_total")
                .tag("component", component)
                .tag("level", level)
                .register(registry)
                .increment();
    }

    private void token(String type, String model, int tokens) {
        if (tokens <= 0) {
            return;
        }
        Counter.builder("llm_tokens_total")
                .tag("type", type)
                .tag("model", safe(model))
                .register(registry)
                .increment(tokens);
    }

    private String safe(String value) {
        return value != null ? value : "unknown";
    }
}

package com.studymate.mcp.metrics;

import com.studymate.common.trace.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * MCP 工具调用埋点：次数 / 耗时 / 失败率，统一打上 tool 与 traceId 维度。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class McpToolMetrics {

    private final MeterRegistry meterRegistry;

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Tool tool = AnnotationUtils.getAnnotation(method, Tool.class);
        String toolName = tool != null && !tool.name().isBlank() ? tool.name() : method.getName();

        Timer.Sample sample = Timer.start(meterRegistry);
        TraceContext.enterSpan("mcp-tool:" + toolName);
        String outcome = "ok";
        try {
            return joinPoint.proceed();
        } catch (Throwable error) {
            outcome = "error";
            throw error;
        } finally {
            TraceContext.exitSpan();
            sample.stop(meterRegistry.timer("studymate.mcp.tool.latency",
                    "tool", toolName, "outcome", outcome));
            meterRegistry.counter("studymate.mcp.tool.calls",
                    "tool", toolName, "outcome", outcome).increment();
        }
    }
}

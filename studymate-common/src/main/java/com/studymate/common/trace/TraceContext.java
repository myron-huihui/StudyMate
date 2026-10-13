package com.studymate.common.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * traceId 贯通 HTTP → 工作流 → LLM → RAG → MCP → SSE。
 * 统一放在 MDC 中，异步线程通过 {@link #wrap(Runnable)} 显式传递。
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN = "span";

    private TraceContext() {
    }

    public static String current() {
        String id = MDC.get(TRACE_ID);
        return id != null ? id : "untraced";
    }

    public static String currentOrCreate() {
        String id = MDC.get(TRACE_ID);
        if (id == null || id.isBlank()) {
            id = newTraceId();
            MDC.put(TRACE_ID, id);
        }
        return id;
    }

    public static void set(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    public static void enterSpan(String span) {
        MDC.put(SPAN, span);
    }

    public static void exitSpan() {
        MDC.remove(SPAN);
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(SPAN);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static Runnable wrap(Runnable task) {
        String traceId = current();
        String span = MDC.get(SPAN);
        return () -> {
            String previousTrace = MDC.get(TRACE_ID);
            String previousSpan = MDC.get(SPAN);
            set(traceId);
            if (span != null) {
                MDC.put(SPAN, span);
            }
            try {
                task.run();
            } finally {
                restore(previousTrace, previousSpan);
            }
        };
    }

    public static <T> Supplier<T> wrap(Supplier<T> task) {
        String traceId = current();
        String span = MDC.get(SPAN);
        return () -> {
            String previousTrace = MDC.get(TRACE_ID);
            String previousSpan = MDC.get(SPAN);
            set(traceId);
            if (span != null) {
                MDC.put(SPAN, span);
            }
            try {
                return task.get();
            } finally {
                restore(previousTrace, previousSpan);
            }
        };
    }

    private static void restore(String traceId, String span) {
        if (traceId != null) {
            MDC.put(TRACE_ID, traceId);
        } else {
            MDC.remove(TRACE_ID);
        }
        if (span != null) {
            MDC.put(SPAN, span);
        } else {
            MDC.remove(SPAN);
        }
    }
}

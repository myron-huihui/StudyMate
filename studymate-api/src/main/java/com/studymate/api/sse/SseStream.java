package com.studymate.api.sse;

import com.studymate.common.sse.SseEventType;
import com.studymate.common.sse.SseMessage;
import com.studymate.common.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 六事件发送器：stage / delta / review / result / done / error。
 * 所有事件共享 traceId 与自增 seq；连接断开后立即拒绝发送，调用方据此中止并不落库。
 */
@Slf4j
public class SseStream {

    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private final SseEmitter emitter;
    private final String traceId;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseStream(long timeoutMs) {
        this.traceId = TraceContext.currentOrCreate();
        this.emitter = new SseEmitter(timeoutMs);
        this.emitter.onCompletion(() -> closed.set(true));
        this.emitter.onTimeout(() -> {
            closed.set(true);
            this.emitter.complete();
        });
        this.emitter.onError(e -> closed.set(true));
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public String traceId() {
        return traceId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 每个阶段执行前调用；已断开则直接抛异常中止后续 LLM 调用与落库 */
    public void ensureOpen() {
        if (closed.get()) {
            throw new StreamAbortedException();
        }
    }

    public void stage(String stage, String content) {
        send(SseEventType.STAGE, stage, content, null, null);
    }

    public void delta(String content) {
        send(SseEventType.DELTA, null, content, null, null);
    }

    public void review(Object payload) {
        send(SseEventType.REVIEW, "review", null, payload, null);
    }

    public void result(Object payload, Map<String, Object> meta) {
        send(SseEventType.RESULT, "result", null, payload, meta);
    }

    public void done(String content) {
        send(SseEventType.DONE, "done", content, null, null);
    }

    public void error(int code, String message) {
        send(SseEventType.ERROR, "error", message, null, Map.of("code", code));
    }

    public void complete() {
        closed.set(true);
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete ignored: {}", e.getMessage());
        }
    }

    public void completeWithError(Throwable error) {
        closed.set(true);
        try {
            emitter.completeWithError(error);
        } catch (Exception e) {
            log.debug("SSE completeWithError ignored: {}", e.getMessage());
        }
    }

    private void send(SseEventType type, String stage, String content, Object payload, Map<String, Object> meta) {
        if (closed.get()) {
            throw new StreamAbortedException();
        }
        SseMessage message = SseMessage.builder()
                .type(type.wireName())
                .traceId(traceId)
                .seq(seq.incrementAndGet())
                .stage(stage)
                .content(content)
                .payload(payload)
                .meta(meta)
                .build();
        try {
            emitter.send(SseEmitter.event()
                    .name(type.wireName())
                    .data(message, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            closed.set(true);
            throw new StreamAbortedException();
        }
    }
}

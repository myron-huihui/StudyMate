package com.studymate.common.sse;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/** 所有 SSE 事件共享的统一信封，traceId 贯穿全程并回传前端 */
@Data
@Builder
public class SseMessage {
    private String type;
    private String traceId;
    private long seq;
    private String stage;
    private String content;
    private Object payload;
    private Map<String, Object> meta;
    @Builder.Default
    private String timestamp = Instant.now().toString();
}

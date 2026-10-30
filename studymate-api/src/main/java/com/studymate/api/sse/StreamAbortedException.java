package com.studymate.api.sse;

/** SSE 连接已断开，后续工作必须立即中止（不得落库） */
public class StreamAbortedException extends RuntimeException {
    public StreamAbortedException() {
        super("SSE 连接已断开");
    }
}

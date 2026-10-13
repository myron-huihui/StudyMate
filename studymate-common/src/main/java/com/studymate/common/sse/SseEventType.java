package com.studymate.common.sse;

/** SSE 六事件契约：stage / delta / review / result / done / error */
public enum SseEventType {
    STAGE("stage"),
    DELTA("delta"),
    REVIEW("review"),
    RESULT("result"),
    DONE("done"),
    ERROR("error");

    private final String wireName;

    SseEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}

package com.studymate.ai.llm;

public class LlmException extends RuntimeException {

    private final int statusCode;
    private final boolean retryable;

    public LlmException(String message, Throwable cause) {
        this(message, cause, 0, true);
    }

    public LlmException(String message, Throwable cause, int statusCode, boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isRateLimited() {
        return statusCode == 429;
    }
}

package com.studymate.common;

import com.studymate.common.trace.TraceContext;
import lombok.Data;

/**
 * 统一响应体，回传 traceId 方便对账
 */
@Data
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(0);
        r.setMessage("success");
        r.setData(data);
        r.setTraceId(TraceContext.current());
        return r;
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        r.setTraceId(TraceContext.current());
        return r;
    }
}

package com.studymate.api.error;

import com.studymate.ai.llm.LlmException;
import com.studymate.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 模型调用失败映射为 502，避免与程序缺陷的 500 混淆，也不向调用方泄露上游报文。
 */
@Slf4j
@RestControllerAdvice
public class LlmExceptionHandler {

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ApiResponse<Void>> handle(LlmException e) {
        log.warn("LLM call failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(502, "模型服务暂不可用，请稍后重试"));
    }
}

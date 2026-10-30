package com.studymate.api.controller;

import com.studymate.ai.llm.LlmTier;
import com.studymate.ai.memory.MemoryService;
import com.studymate.api.config.SseExecutorConfig;
import com.studymate.api.security.UserContext;
import com.studymate.api.service.PlanOrchestratorService;
import com.studymate.api.sse.SseStream;
import com.studymate.api.sse.StreamAbortedException;
import com.studymate.common.dto.ChatRequest;
import com.studymate.common.dto.CreatePlanRequest;
import com.studymate.common.trace.TraceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流式控制器：计划生成 + 带记忆的对话
 */
@Slf4j
@Tag(name = "SSE 流式", description = "stage / delta / review / result / done / error 六事件")
@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class StreamController {

    private final PlanOrchestratorService planOrchestrator;
    private final MemoryService memoryService;
    @Qualifier(SseExecutorConfig.SSE_EXECUTOR)
    private final ThreadPoolTaskExecutor sseExecutor;

    @Operation(summary = "流式创建学习计划")
    @PostMapping(value = "/plans", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createPlanStream(@Valid @RequestBody CreatePlanRequest req) {
        return planOrchestrator.createPlanStream(UserContext.currentUserId(), req);
    }

    @Operation(summary = "流式对话（带记忆，delta 逐字推送）")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest req) {
        SseStream stream = new SseStream(SseStream.DEFAULT_TIMEOUT_MS);
        sseExecutor.execute(TraceContext.wrap(() -> {
            try {
                stream.stage("start", "开始生成回答");
                LlmTier tier = req.getTier() == null ? LlmTier.BALANCED
                        : LlmTier.valueOf(req.getTier().toUpperCase());
                memoryService.streamChat(req.getMemoryId(), UserContext.currentUserId(), req.getMessage(),
                                tier, req.getModelName())
                        .subscribe(
                                delta -> {
                                    if (stream.isClosed()) {
                                        throw new StreamAbortedException();
                                    }
                                    stream.delta(delta);
                                },
                                error -> {
                                    if (error instanceof StreamAbortedException || stream.isClosed()) {
                                        log.info("SSE chat stream aborted, memoryId={}", req.getMemoryId());
                                    } else {
                                        log.error("SSE chat stream failed, memoryId={}", req.getMemoryId(), error);
                                        try {
                                            stream.error(500, error.getMessage());
                                        } catch (StreamAbortedException ignored) {
                                        }
                                    }
                                    stream.complete();
                                },
                                () -> {
                                    try {
                                        stream.done("回答完成");
                                    } catch (StreamAbortedException ignored) {
                                    }
                                    stream.complete();
                                });
            } catch (StreamAbortedException e) {
                log.info("SSE chat stream aborted, memoryId={}", req.getMemoryId());
            } catch (Exception e) {
                log.error("SSE chat stream failed, memoryId={}", req.getMemoryId(), e);
                try {
                    stream.error(500, e.getMessage());
                } catch (StreamAbortedException ignored) {
                }
                stream.complete();
            }
        }));
        return stream.emitter();
    }
}

package com.studymate.api.controller;

import com.studymate.ai.llm.LlmTier;
import com.studymate.ai.memory.MemoryService;
import com.studymate.ai.memory.MemoryTurn;
import com.studymate.api.security.UserContext;
import com.studymate.common.ApiResponse;
import com.studymate.common.dto.ChatRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 记忆层接口：memoryId 隔离、短期窗口、全量历史、清空
 */
@Tag(name = "对话记忆", description = "memoryId 隔离的短期窗口与全量历史")
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    @Operation(summary = "发送消息（非流式，自动读写记忆）")
    @PostMapping("/chat")
    public ApiResponse<MemoryService.MemoryReply> chat(@Valid @RequestBody ChatRequest req) {
        LlmTier tier = req.getTier() == null ? LlmTier.BALANCED : LlmTier.valueOf(req.getTier().toUpperCase());
        return ApiResponse.ok(memoryService.chat(
                req.getMemoryId(), UserContext.currentUserId(), req.getMessage(), tier, req.getModelName()));
    }

    @Operation(summary = "查看最近窗口（Redis 优先，miss 回源 MySQL）")
    @GetMapping("/{memoryId}/window")
    public ApiResponse<List<MemoryTurn>> window(@PathVariable String memoryId) {
        return ApiResponse.ok(memoryService.window(memoryId, UserContext.currentUserId()));
    }

    @Operation(summary = "查看全量历史")
    @GetMapping("/{memoryId}/history")
    public ApiResponse<List<MemoryTurn>> history(@PathVariable String memoryId,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(memoryService.history(memoryId, UserContext.currentUserId(), limit));
    }

    @Operation(summary = "清空记忆")
    @DeleteMapping("/{memoryId}")
    public ApiResponse<Void> clear(@PathVariable String memoryId) {
        memoryService.clear(memoryId, UserContext.currentUserId());
        return ApiResponse.ok();
    }
}

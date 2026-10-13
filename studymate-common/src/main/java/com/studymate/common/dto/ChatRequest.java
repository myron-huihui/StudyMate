package com.studymate.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 带记忆的对话请求 */
@Data
@Schema(description = "对话请求")
public class ChatRequest {

    @NotBlank(message = "memoryId 不能为空")
    @Schema(description = "记忆空间 ID，用于隔离上下文", example = "chat-001")
    private String memoryId;

    @NotBlank(message = "消息不能为空")
    @Schema(description = "用户消息")
    private String message;

    @Schema(description = "指定模型名（可选，切换模型不丢上下文）")
    private String modelName;

    @Schema(description = "档位：FAST / BALANCED / STRONG（可选）")
    private String tier;
}

package com.studymate.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 创建学习计划请求
 */
@Data
@Schema(description = "创建学习计划请求")
public class CreatePlanRequest {

    @NotBlank(message = "学习目标不能为空")
    @Schema(description = "学习目标", example = "三个月学会 Python 爬虫")
    private String goal;

    @NotNull(message = "每日可用时间不能为空")
    @Positive(message = "可用时间必须大于 0")
    @Schema(description = "每日可用时间（小时）", example = "2.5")
    private Double dailyHours;

    @NotBlank(message = "当前基础不能为空")
    @Schema(description = "当前基础描述", example = "会基础语法，没写过项目")
    private String currentLevel;

    @Schema(description = "期望天数（可选，不填由 AI 推断）")
    private Integer totalDays;

    @Schema(description = "记忆空间 ID（可选，传入后复用历史对话上下文）")
    private String memoryId;
}

package com.studymate.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 每日任务响应
 */
@Data
@Schema(description = "每日任务")
public class DailyTaskResponse {
    private Long id;
    private Integer dayIndex;
    private LocalDate date;
    private String title;
    private String description;
    private Integer estimatedMinutes;
    private String status;
    private Boolean checkedIn;
}

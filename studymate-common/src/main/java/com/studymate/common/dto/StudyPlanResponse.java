package com.studymate.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 学习计划响应
 */
@Data
@Schema(description = "学习计划")
public class StudyPlanResponse {
    private Long id;
    private String title;
    private String goal;
    private String goalInterpreted;
    private Double dailyHours;
    private String currentLevel;
    private Integer totalDays;
    private String status;
    private Integer progress;
    private List<DailyTaskResponse> dailyTasks;
}

package com.studymate.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习计划表
 */
@Data
@TableName("t_study_plan")
public class StudyPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    /** 学习目标（原文） */
    private String goal;
    /** AI 理解后的目标描述 */
    private String goalInterpreted;
    /** 可用时间（小时/天） */
    private Double dailyHours;
    /** 当前基础描述 */
    private String currentLevel;
    /** 计划总天数 */
    private Integer totalDays;
    /** 状态：DRAFT/ACTIVE/COMPLETED/ARCHIVED */
    private String status;
    /** 完成进度 0-100 */
    private Integer progress;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

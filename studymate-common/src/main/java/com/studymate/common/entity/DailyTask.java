package com.studymate.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日任务表
 */
@Data
@TableName("t_daily_task")
public class DailyTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    /** 第几天 */
    private Integer dayIndex;
    private LocalDate date;
    /** 任务标题 */
    private String title;
    /** 任务详细描述 */
    private String description;
    /** 预估耗时（分钟） */
    private Integer estimatedMinutes;
    /** 状态：PENDING/IN_PROGRESS/DONE/MISS */
    private String status;
    /** 是否已打卡 */
    private Boolean checkedIn;
    /** 打卡时间 */
    private LocalDateTime checkedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

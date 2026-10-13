package com.studymate.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打卡记录：唯一键 (task_id, user_id) 保证数据库级幂等
 */
@Data
@TableName("t_task_checkin")
public class TaskCheckin {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long userId;
    private LocalDateTime createdAt;
}

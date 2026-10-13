package com.studymate.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话记忆全量历史：与 Redis 窗口互为兜底，切换模型不丢上下文 */
@Data
@TableName("t_chat_memory")
public class ChatMemoryRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String memoryId;
    private Long userId;
    /** user / assistant / system */
    private String role;
    private String content;
    /** 产生该条回复的模型（仅审计，不参与上下文） */
    private String model;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

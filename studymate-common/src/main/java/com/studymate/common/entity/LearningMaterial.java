package com.studymate.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习资料表（知识库文档）
 */
@Data
@TableName("t_learning_material")
public class LearningMaterial {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String summary;
    /** 分类：VIDEO/ARTICLE/BOOK/COURSE/DOCUMENT */
    private String category;
    /** 来源 URL 或标识 */
    private String source;
    /** 难度 1-5 */
    private Integer difficulty;
    /** 推荐次数 */
    private Integer recommendCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

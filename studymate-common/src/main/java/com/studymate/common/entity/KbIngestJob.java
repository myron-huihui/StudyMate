package com.studymate.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库离线入库任务：每完成一个阶段即持久化，重启后可从断点继续。
 */
@Data
@TableName("t_kb_ingest_job")
public class KbIngestJob {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourcePath;
    private String title;
    private String category;
    private String fileHash;
    private String contentHash;
    private Long materialId;
    /** SANITIZE / DEDUP / CHUNK / ENHANCE / EMBED / STORE / DONE */
    private String stage;
    /** RUNNING / COMPLETED / FAILED / SKIPPED */
    private String status;
    private String cleanText;
    private String chunksJson;
    private String enhancedJson;
    private String errorMessage;
    private Integer retryCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

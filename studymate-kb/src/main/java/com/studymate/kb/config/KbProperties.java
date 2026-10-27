package com.studymate.kb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "kb")
public class KbProperties {
    /** 知识库根目录（本地文件扫描） */
    private String basePath = "./data/kb";
    /** 支持的文件后缀 */
    private String[] extensions = {".pdf", ".docx", ".md", ".txt", ".html", ".pptx"};
    /** 是否自动扫描入库 */
    private boolean autoScan = false;
    /** 父块大小（字符） */
    private int parentChunkSize = 1200;
    /** 子块大小（字符） */
    private int childChunkSize = 320;
    /** 子块重叠（字符） */
    private int childOverlap = 64;
    /** 元数据批量增强：每次 LLM 调用处理的父块数量 */
    private int enhanceBatchSize = 5;
    /** 单文档失败自动重试次数 */
    private int maxRetries = 1;
}

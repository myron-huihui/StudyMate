package com.studymate.ai.rag;

import lombok.Data;

@Data
public class RagResult {
    private Long materialId;
    private Integer chunkIndex;
    private String parentId;
    private String title;
    private String category;
    /** 用于拼上下文的内容（已压缩） */
    private String content;
    private String parentContent;
    private Double vectorScore;
    private Double bm25Score;
    private Double rrfScore;
    private Double rerankScore;
    private String source;
    /** 精排实际生效层级：REMOTE / LOCAL / RRF */
    private String rerankLevel;
}

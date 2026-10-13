package com.studymate.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 资料推荐响应
 */
@Data
@Schema(description = "资料推荐")
public class MaterialRecommendResponse {
    private Long materialId;
    private String title;
    private String category;
    private String source;
    private Integer difficulty;
    /** 推荐理由 */
    private String reason;
    /** 相关度分数 0-1 */
    private Double score;
}

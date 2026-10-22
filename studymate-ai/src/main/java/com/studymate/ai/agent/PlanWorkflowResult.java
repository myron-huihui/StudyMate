package com.studymate.ai.agent;

import lombok.Builder;
import lombok.Data;

/** 计划工作流产出的最终结果 */
@Data
@Builder
public class PlanWorkflowResult {
    private String understanding;
    private String planJson;
    private ReviewResult review;
    private int rewriteCount;
    private boolean passed;
    /** 审查降级或重写超限，计划仍可用但建议人工复核 */
    private boolean needsManual;
}

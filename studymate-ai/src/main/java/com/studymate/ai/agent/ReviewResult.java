package com.studymate.ai.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/** 四维质量审查结果 */
@Data
@AllArgsConstructor
public class ReviewResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean passed;
    private Map<String, Integer> scores;
    private int average;
    private String critique;
    /** 审查链路自身降级（LLM 不可用/解析失败），计划仍可用但需人工复核 */
    private boolean degraded;

    public static ReviewResult degraded(String reason) {
        return new ReviewResult(true, Map.of(), 0, reason, true);
    }
}

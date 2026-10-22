package com.studymate.ai.service;

import com.studymate.ai.config.AiProperties;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 每日任务拆解服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDecompositionService {

    private final LlmGateway llmGateway;

    private static final String SYSTEM_PROMPT = """
            你是任务拆解专家。给定一个阶段学习目标和每日可用时间，
            将目标拆解为逐日可执行的任务列表。
            输出严格 JSON 数组：
            [
              {
                "day_index": int,
                "title": "任务标题（15字以内）",
                "description": "具体步骤（编号列表，每步含预期产出）",
                "estimated_minutes": int,
                "materials": ["推荐资料关键词1", "关键词2"],
                "done_criteria": "完成标准（一句话）"
              }
            ]
            要求：
            - 每日总耗时 ≤ 可用时间
            - 任务粒度适中（20-90分钟）
            - 标注每日需要查什么资料
            - 有递进关系，后一日依赖前一日成果
            """;

    /**
     * 拆解阶段任务为每日可执行项
     */
    public String decompose(String phaseName, String phaseFocus, int days, double dailyHours, String context) {
        String userPrompt = String.format("""
                阶段：%s
                重点：%s
                天数：%d 天
                每日可用时间：%.1f 小时
                上下文：%s
                请拆解为 %d 天的每日任务。
                """, phaseName, phaseFocus, days, dailyHours, context, days);
        return llmGateway.chat(SYSTEM_PROMPT, userPrompt, LlmTier.BALANCED);
    }
}

package com.studymate.ai.service;

import com.studymate.ai.config.AiProperties;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 目标理解服务：将用户原始目标解析为结构化学习计划
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalUnderstandingService {

    private final LlmGateway llmGateway;
    private final AiProperties aiProperties;

    private static final String SYSTEM_PROMPT = """
            你是一名资深学习规划师。用户会提供三类信息：
            1. 学习目标
            2. 每日可用时间
            3. 当前基础
            
            请输出严格 JSON（不要 markdown 包裹），包含：
            {
              "title": "计划标题（10字以内）",
              "goal_interpreted": "对目标的完整理解与拆解说明",
              "total_days": 建议总天数(int),
              "phases": [
                { "name": "阶段名", "days": 天数, "focus": "重点内容" }
              ],
              "daily_focus": "每日核心任务的一句话描述"
            }
            确保 total_days 与 dailyHours 匹配，任务可执行、不过载。
            """;

    /**
     * 理解用户学习目标
     */
    public String understand(String goal, Double dailyHours, String currentLevel, Integer totalDays) {
        String userPrompt = String.format("""
                学习目标：%s
                每日可用时间：%.1f 小时
                当前基础：%s
                %s
                """, goal, dailyHours, currentLevel,
                totalDays != null ? "期望天数：" + totalDays + " 天" : "天数由你根据目标难度和可用时间推断");
        String raw = llmGateway.chat(SYSTEM_PROMPT, userPrompt, LlmTier.FAST, 1200, 40_000, 2);
        log.debug("Goal understanding raw: {}", raw);
        return raw;
    }
}

package com.studymate.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质量审查：完整性 / 可执行性 / 结构 / 具体性 四维打分。
 * 审查自身失败不阻塞交付，只标记 degraded 由上层置 needsManual。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityReviewService {

    private static final int PASS_AVERAGE = 75;
    private static final int PASS_MIN_DIMENSION = 60;
    private static final int REVIEW_MAX_TOKENS = 600;
    private static final int REVIEW_TIMEOUT_MS = 30_000;
    private static final int REVIEW_MAX_CANDIDATES = 2;
    private static final int DIGEST_MAX_CHARS = 8_000;

    private static final String SYSTEM_PROMPT = """
            你是学习计划质量审查员。对给定计划按四个维度打分（0-100）：
            1. completeness 完整性：知识覆盖是否匹配目标，是否有阶段与里程碑
            2. feasibility 可执行性：每日任务量是否不超过用户可用时间，难度是否递进
            3. structure 结构：day_index 是否从 1 连续、阶段划分是否清晰
            4. specificity 具体性：任务是否有可执行步骤、完成标准与产出
            输出严格 JSON（不要 markdown 包裹）：
            {
              "scores": {"completeness": 0, "feasibility": 0, "structure": 0, "specificity": 0},
              "passed": true,
              "critique": "不通过时给出具体问题清单与修改建议；通过时为空字符串"
            }
            通过标准：平均分 >= 75 且任一维度 >= 60。
            """;

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public ReviewResult review(String planJson, String goal, double dailyHours) {
        StructuralCheck structural = structuralCheck(planJson, dailyHours);

        String userPrompt = """
                学习目标：%s
                每日可用时间：%.1f 小时
                待审查计划摘要：
                %s
                """.formatted(goal, dailyHours, buildDigest(planJson));

        try {
            String raw = llmGateway.chat(SYSTEM_PROMPT, userPrompt, LlmTier.FAST,
                    REVIEW_MAX_TOKENS, REVIEW_TIMEOUT_MS, REVIEW_MAX_CANDIDATES);
            JsonNode root = parseJson(raw);
            Map<String, Integer> scores = readScores(root.path("scores"));

            // 结构维度取"LLM 打分"与"确定性校验"的较低值
            scores.merge("structure", structural.score(), Math::min);
            int average = (int) Math.round(scores.values().stream().mapToInt(Integer::intValue).average().orElse(0));
            int min = scores.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            boolean passed = root.path("passed").asBoolean(false)
                    && average >= PASS_AVERAGE
                    && min >= PASS_MIN_DIMENSION
                    && structural.valid();

            String critique = root.path("critique").asText("");
            if (!structural.valid()) {
                critique = (critique.isBlank() ? "" : critique + "；") + structural.message();
            }
            return new ReviewResult(passed, scores, average, critique, false);
        } catch (Exception e) {
            log.warn("Quality review degraded: {}", e.getMessage());
            return ReviewResult.degraded("质量审查不可用：" + e.getMessage());
        }
    }

    /** 压缩计划为审查摘要，避免整份长周期计划 JSON 拉高时延 */
    private String buildDigest(String planJson) {
        try {
            JsonNode root = objectMapper.readTree(planJson);
            StringBuilder sb = new StringBuilder(2048);
            sb.append("title: ").append(root.path("title").asText("")).append('\n');
            sb.append("total_days: ").append(root.path("total_days").asInt(0)).append('\n');
            JsonNode phases = root.path("phases");
            if (phases.isArray()) {
                for (JsonNode phase : phases) {
                    sb.append("- 阶段 ").append(phase.path("name").asText(""))
                            .append("（").append(phase.path("days").asInt(0)).append(" 天）: ")
                            .append(phase.path("focus").asText("")).append('\n');
                }
            }
            JsonNode milestones = root.path("milestones");
            if (milestones.isArray()) {
                for (JsonNode milestone : milestones) {
                    sb.append("- 里程碑 D").append(milestone.path("day_index").asInt(0))
                            .append(": ").append(milestone.path("description").asText("")).append('\n');
                }
            }
            JsonNode tasks = root.path("daily_tasks");
            if (tasks.isArray()) {
                for (JsonNode task : tasks) {
                    sb.append("D").append(task.path("day_index").asInt(0))
                            .append(' ').append(truncate(task.path("title").asText(""), 40))
                            .append("（").append(task.path("estimated_minutes").asInt(0)).append("min）\n");
                    if (sb.length() >= DIGEST_MAX_CHARS) {
                        sb.append("...(其余任务省略)\n");
                        break;
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Build review digest failed, fallback to raw plan: {}", e.getMessage());
            return planJson.length() > DIGEST_MAX_CHARS ? planJson.substring(0, DIGEST_MAX_CHARS) : planJson;
        }
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private JsonNode parseJson(String raw) throws Exception {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("review output is not json");
        }
        return objectMapper.readTree(raw.substring(start, end + 1));
    }

    private Map<String, Integer> readScores(JsonNode node) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String key : new String[]{"completeness", "feasibility", "structure", "specificity"}) {
            scores.put(key, clamp(node.path(key).asInt(50)));
        }
        return scores;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private StructuralCheck structuralCheck(String planJson, double dailyHours) {
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode tasks = root.path("daily_tasks");
            if (!tasks.isArray() || tasks.isEmpty()) {
                return new StructuralCheck(false, 40, "daily_tasks 缺失或为空");
            }
            int expected = 1;
            int score = 100;
            int maxMinutes = (int) Math.round(dailyHours * 60 * 1.2);
            for (JsonNode task : tasks) {
                int dayIndex = task.path("day_index").asInt(0);
                if (dayIndex != expected) {
                    score -= 10;
                }
                expected = dayIndex + 1;
                if (task.path("estimated_minutes").asInt(0) > maxMinutes) {
                    score -= 5;
                }
                if (task.path("title").asText("").isBlank()) {
                    score -= 5;
                }
            }
            boolean valid = score >= 75;
            return new StructuralCheck(valid, clamp(score),
                    valid ? "" : "存在 day_index 不连续、任务超时或标题缺失");
        } catch (Exception e) {
            return new StructuralCheck(false, 40, "计划 JSON 无法解析");
        }
    }

    private record StructuralCheck(boolean valid, int score, String message) {
    }
}

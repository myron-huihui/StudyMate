package com.studymate.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmTier;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 计划生成服务：根据目标理解结果生成完整学习计划。
 * 长周期计划按 {@link #CHUNK_DAYS} 天分片并行生成后合并，
 * 避免单次调用超出模型输出上限导致 JSON 截断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanGenerationService {

    private static final int CHUNK_DAYS = 15;
    private static final int MAX_PARALLEL = 6;
    private static final int CHUNK_MAX_TOKENS = 8192;
    private static final int CHUNK_TIMEOUT_MS = 75_000;
    private static final int CHUNK_ATTEMPTS = 2;

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    private final ExecutorService chunkExecutor = Executors.newFixedThreadPool(MAX_PARALLEL, r -> {
        Thread thread = new Thread(r, "plan-chunk");
        thread.setDaemon(true);
        return thread;
    });

    private static final String SYSTEM_PROMPT = """
            你是学习规划师，根据用户目标理解结果，生成按天拆解的完整学习计划。
            输出严格 JSON：
            {
              "title": "计划标题",
              "goal_interpreted": "目标说明",
              "total_days": int,
              "phases": [
                {
                  "name": "阶段名",
                  "days": int,
                  "focus": "重点"
                }
              ],
              "daily_tasks": [
                {
                  "day_index": 1,
                  "title": "任务标题",
                  "description": "具体执行步骤（3-5条）",
                  "estimated_minutes": int
                }
              ],
              "milestones": [
                { "day_index": int, "description": "里程碑描述" }
              ]
            }
            要求：
            - 每日任务总量不超过可用时间
            - 难度递进，先基础后进阶
            - 每个任务可独立执行，有明确完成标准
            - day_index 从 1 开始连续
            """;

    @PreDestroy
    void shutdown() {
        chunkExecutor.shutdownNow();
    }

    /**
     * 生成完整计划
     */
    public String generate(String goal, Double dailyHours, String currentLevel, Integer totalDays, String goalInterpreted) {
        return generate(goal, dailyHours, currentLevel, totalDays, goalInterpreted, "", "");
    }

    /**
     * 生成完整计划：支持对话记忆上下文与审查不通过后的重写指令
     */
    public String generate(String goal, Double dailyHours, String currentLevel, Integer totalDays,
                           String goalInterpreted, String memoryContext, String critique) {
        int days = resolveDays(totalDays, goalInterpreted);
        if (days <= CHUNK_DAYS) {
            return extractJson(generateRange(goal, dailyHours, currentLevel,
                    days > 0 ? Integer.valueOf(days) : null, goalInterpreted, memoryContext, critique, 0, 0));
        }
        return generateChunked(goal, dailyHours, currentLevel, days, goalInterpreted, memoryContext, critique);
    }

    private String generateChunked(String goal, Double dailyHours, String currentLevel, int totalDays,
                                   String goalInterpreted, String memoryContext, String critique) {
        List<ChunkJob> jobs = new ArrayList<>();
        for (int start = 1; start <= totalDays; start += CHUNK_DAYS) {
            int end = Math.min(totalDays, start + CHUNK_DAYS - 1);
            jobs.add(new ChunkJob(start, end));
        }
        List<Future<ObjectNode>> futures = new ArrayList<>(jobs.size());
        for (ChunkJob job : jobs) {
            futures.add(chunkExecutor.submit(() -> generateChunkWithRetry(
                    goal, dailyHours, currentLevel, totalDays, goalInterpreted, memoryContext, critique, job)));
        }

        List<ChunkResult> chunks = new ArrayList<>(jobs.size());
        for (int i = 0; i < jobs.size(); i++) {
            ChunkJob job = jobs.get(i);
            try {
                chunks.add(new ChunkResult(job, futures.get(i).get()));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IllegalStateException("计划分片生成失败（第 " + job.start() + "-" + job.end() + " 天）："
                        + cause.getMessage(), cause);
            }
        }
        return merge(chunks, totalDays);
    }

    private ObjectNode generateChunkWithRetry(String goal, Double dailyHours, String currentLevel, int totalDays,
                                              String goalInterpreted, String memoryContext, String critique,
                                              ChunkJob job) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= CHUNK_ATTEMPTS; attempt++) {
            try {
                String raw = generateRange(goal, dailyHours, currentLevel, totalDays,
                        goalInterpreted, memoryContext, critique, job.start(), job.end());
                return parseChunk(raw);
            } catch (RuntimeException e) {
                last = e;
                log.warn("Plan chunk {}-{} attempt {}/{} failed: {}",
                        job.start(), job.end(), attempt, CHUNK_ATTEMPTS, e.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("分片生成失败");
    }

    private ObjectNode parseChunk(String raw) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalStateException("分片输出不是 JSON");
            }
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            if (!node.isObject()) {
                throw new IllegalStateException("分片输出不是 JSON 对象");
            }
            return (ObjectNode) node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("分片输出解析失败：" + e.getMessage(), e);
        }
    }

    private String merge(List<ChunkResult> chunks, int totalDays) {
        ObjectNode merged = objectMapper.createObjectNode();
        ChunkResult first = chunks.get(0);
        merged.put("title", first.node().path("title").asText("学习计划"));
        merged.put("goal_interpreted", first.node().path("goal_interpreted").asText(""));
        merged.put("total_days", totalDays);

        ArrayNode phases = merged.putArray("phases");
        TreeMap<Integer, JsonNode> tasks = new TreeMap<>();
        TreeMap<Integer, JsonNode> milestones = new TreeMap<>();

        for (ChunkResult chunk : chunks) {
            JsonNode phaseNodes = chunk.node().path("phases");
            if (phaseNodes.isArray()) {
                phaseNodes.forEach(phases::add);
            }
            collectByDay(chunk.node().path("daily_tasks"), chunk.job(), tasks);
            collectByDay(chunk.node().path("milestones"), chunk.job(), milestones);
        }

        ArrayNode dailyTasks = merged.putArray("daily_tasks");
        tasks.values().forEach(dailyTasks::add);
        ArrayNode milestoneArray = merged.putArray("milestones");
        milestones.values().forEach(milestoneArray::add);

        if (tasks.size() < totalDays) {
            log.warn("Plan merge coverage {}/{} days", tasks.size(), totalDays);
        }
        return merged.toString();
    }

    private void collectByDay(JsonNode nodes, ChunkJob job, Map<Integer, JsonNode> target) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            int dayIndex = node.path("day_index").asInt(0);
            if (dayIndex >= job.start() && dayIndex <= job.end()) {
                target.putIfAbsent(dayIndex, node);
            }
        }
    }

    private String generateRange(String goal, Double dailyHours, String currentLevel, Integer totalDays,
                                 String goalInterpreted, String memoryContext, String critique,
                                 int startDay, int endDay) {
        String memoryBlock = memoryContext == null || memoryContext.isBlank()
                ? ""
                : "历史对话（保持用户偏好与既有约束）：\n" + memoryContext + "\n";
        String rewriteBlock = critique == null || critique.isBlank()
                ? ""
                : """
                上一版计划未通过质量审查，必须逐条修正以下问题后重新输出完整计划：
                %s
                """.formatted(critique);
        String rangeBlock = startDay > 0
                ? """
                本次只生成第 %d-%d 天：
                - daily_tasks 的 day_index 从 %d 开始连续到 %d，不要输出区间外的天
                - phases 只描述覆盖该区间的阶段，days 为该区间内天数
                - milestones 只包含该区间内的里程碑
                - 每个任务 description 最多 5 条步骤，每条不超过 25 字，保持紧凑
                """.formatted(startDay, endDay, startDay, endDay)
                : "";

        String userPrompt = String.format("""
                原始目标：%s
                目标理解：%s
                每日可用时间：%.1f 小时
                当前基础：%s
                %s
                %s
                %s%s请生成完整按天拆解的学习计划。
                """, goal, goalInterpreted, dailyHours, currentLevel,
                totalDays != null ? "总天数：" + totalDays : "天数由你推断",
                rangeBlock, memoryBlock, rewriteBlock);
        String raw = llmGateway.chat(SYSTEM_PROMPT, userPrompt, LlmTier.FAST,
                CHUNK_MAX_TOKENS, CHUNK_TIMEOUT_MS, 2);
        log.debug("Plan generation raw: {}", raw);
        return raw;
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw.trim();
    }

    private int resolveDays(Integer totalDays, String goalInterpreted) {
        if (totalDays != null && totalDays > 0) {
            return totalDays;
        }
        if (goalInterpreted != null && !goalInterpreted.isBlank()) {
            try {
                int start = goalInterpreted.indexOf('{');
                int end = goalInterpreted.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    int days = objectMapper.readTree(goalInterpreted.substring(start, end + 1))
                            .path("total_days").asInt(0);
                    if (days > 0) {
                        return days;
                    }
                }
            } catch (Exception e) {
                log.debug("Resolve total_days from understanding failed: {}", e.getMessage());
            }
        }
        return 0;
    }

    private record ChunkJob(int start, int end) {
    }

    private record ChunkResult(ChunkJob job, ObjectNode node) {
    }
}

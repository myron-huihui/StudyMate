package com.studymate.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.ai.agent.PlanAgentListener;
import com.studymate.ai.agent.PlanAgentWorkflow;
import com.studymate.ai.agent.PlanWorkflowResult;
import com.studymate.ai.agent.ReviewResult;
import com.studymate.ai.memory.MemoryService;
import com.studymate.api.config.SseExecutorConfig;
import com.studymate.api.sse.SseStream;
import com.studymate.api.sse.StreamAbortedException;
import com.studymate.common.dto.CreatePlanRequest;
import com.studymate.common.dto.DailyTaskResponse;
import com.studymate.common.dto.StudyPlanResponse;
import com.studymate.common.entity.DailyTask;
import com.studymate.common.entity.StudyPlan;
import com.studymate.common.exception.BusinessException;
import com.studymate.common.trace.TraceContext;
import com.studymate.dal.mapper.DailyTaskMapper;
import com.studymate.dal.mapper.StudyPlanMapper;
import com.studymate.dal.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 计划编排服务：Agent 工作流（理解→生成→审查→重写→定稿）→ 入库。
 * 流式入口立即返回 emitter，工作流在独立线程池执行，断连即中止且不落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanOrchestratorService {

    private final PlanAgentWorkflow planAgentWorkflow;
    private final MemoryService memoryService;
    private final StudyPlanMapper planMapper;
    private final DailyTaskMapper taskMapper;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    @Qualifier(SseExecutorConfig.SSE_EXECUTOR)
    private final ThreadPoolTaskExecutor sseExecutor;

    /**
     * 同步创建计划（非流式）
     */
    public StudyPlanResponse createPlan(Long userId, CreatePlanRequest req) {
        String memoryContext = loadMemoryContext(userId, req.getMemoryId());
        PlanWorkflowResult result = planAgentWorkflow.execute(
                req.getGoal(), req.getDailyHours(), req.getCurrentLevel(), req.getTotalDays(),
                memoryContext, PlanAgentListener.NOOP);
        StudyPlanResponse response = parseAndSave(userId, req, result);
        recordPlanMemory(userId, req, result);
        return response;
    }

    /**
     * 流式创建计划：立即返回 emitter，后台线程推送 stage/review/result/done/error
     */
    public SseEmitter createPlanStream(Long userId, CreatePlanRequest req) {
        SseStream stream = new SseStream(SseStream.DEFAULT_TIMEOUT_MS);
        sseExecutor.execute(TraceContext.wrap(() -> runPlanStream(userId, req, stream)));
        return stream.emitter();
    }

    private void runPlanStream(Long userId, CreatePlanRequest req, SseStream stream) {
        try {
            stream.stage("start", "开始生成学习计划");
            PlanAgentListener listener = new PlanAgentListener() {
                @Override
                public void onStage(String stage, String message) {
                    stream.ensureOpen();
                    stream.stage(stage, message);
                }

                @Override
                public void onReview(Map<String, Object> review) {
                    stream.ensureOpen();
                    stream.review(review);
                }
            };

            String memoryContext = loadMemoryContext(userId, req.getMemoryId());
            PlanWorkflowResult result = planAgentWorkflow.execute(
                    req.getGoal(), req.getDailyHours(), req.getCurrentLevel(), req.getTotalDays(),
                    memoryContext, listener);

            // 客户端已断开：丢弃结果，不写库
            stream.ensureOpen();
            StudyPlanResponse response = parseAndSave(userId, req, result);
            recordPlanMemory(userId, req, result);

            stream.result(response, reviewMeta(result));
            stream.done("计划生成完成");
        } catch (StreamAbortedException e) {
            log.info("SSE plan stream aborted by client, userId={}, goal={}", userId, req.getGoal());
        } catch (Exception e) {
            log.error("SSE plan stream failed, userId={}", userId, e);
            try {
                stream.error(500, e.getMessage());
            } catch (StreamAbortedException ignored) {
                // 连接已断开，无需再推送
            }
        } finally {
            stream.complete();
        }
    }

    private Map<String, Object> reviewMeta(PlanWorkflowResult result) {
        Map<String, Object> meta = new LinkedHashMap<>();
        ReviewResult review = result.getReview();
        meta.put("passed", result.isPassed());
        meta.put("needsManual", result.isNeedsManual());
        meta.put("rewriteCount", result.getRewriteCount());
        if (review != null) {
            meta.put("average", review.getAverage());
            meta.put("scores", review.getScores());
            meta.put("degraded", review.isDegraded());
            meta.put("critique", review.getCritique());
        }
        return meta;
    }

    private String loadMemoryContext(Long userId, String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return "";
        }
        try {
            return memoryService.contextPrompt(memoryId, userId);
        } catch (Exception e) {
            log.warn("Load memory context failed, memoryId={}: {}", memoryId, e.getMessage());
            return "";
        }
    }

    private void recordPlanMemory(Long userId, CreatePlanRequest req, PlanWorkflowResult result) {
        String memoryId = req.getMemoryId();
        if (memoryId == null || memoryId.isBlank()) {
            return;
        }
        try {
            memoryService.append(memoryId, userId, "user",
                    "学习目标：" + req.getGoal() + "；每日 " + req.getDailyHours() + " 小时；基础："
                            + req.getCurrentLevel(), null);
            memoryService.append(memoryId, userId, "assistant", result.getPlanJson(), null);
        } catch (Exception e) {
            log.warn("Record plan memory failed, memoryId={}: {}", memoryId, e.getMessage());
        }
    }

    private StudyPlanResponse parseAndSave(Long userId, CreatePlanRequest req, PlanWorkflowResult result) {
        JsonNode json = extractJson(result.getPlanJson());
        String title = json.path("title").asText("学习计划");
        int totalDays = json.path("total_days").asInt(30);

        StudyPlan plan = new StudyPlan();
        plan.setUserId(userId);
        plan.setTitle(title);
        plan.setGoal(req.getGoal());
        plan.setGoalInterpreted(json.path("goal_interpreted").asText(""));
        plan.setDailyHours(req.getDailyHours());
        plan.setCurrentLevel(req.getCurrentLevel());
        plan.setTotalDays(totalDays);
        plan.setStatus("ACTIVE");
        plan.setProgress(0);
        planMapper.insert(plan);
        redisService.cachePlan(plan);

        JsonNode dailyTasks = json.path("daily_tasks");
        List<DailyTaskResponse> taskResponses = new ArrayList<>();
        if (dailyTasks.isArray()) {
            LocalDate start = LocalDate.now().plusDays(1);
            for (int i = 0; i < dailyTasks.size(); i++) {
                JsonNode t = dailyTasks.get(i);
                DailyTask task = new DailyTask();
                task.setPlanId(plan.getId());
                task.setDayIndex(t.path("day_index").asInt(i + 1));
                task.setDate(start.plusDays(i));
                task.setTitle(t.path("title").asText("第 " + (i + 1) + " 天任务"));
                task.setDescription(t.path("description").asText(""));
                task.setEstimatedMinutes(t.path("estimated_minutes").asInt(30));
                task.setStatus("PENDING");
                task.setCheckedIn(false);
                taskMapper.insert(task);

                DailyTaskResponse tr = new DailyTaskResponse();
                tr.setId(task.getId());
                tr.setDayIndex(task.getDayIndex());
                tr.setDate(task.getDate());
                tr.setTitle(task.getTitle());
                tr.setDescription(task.getDescription());
                tr.setEstimatedMinutes(task.getEstimatedMinutes());
                tr.setStatus(task.getStatus());
                tr.setCheckedIn(task.getCheckedIn());
                taskResponses.add(tr);
            }
        }

        StudyPlanResponse resp = new StudyPlanResponse();
        resp.setId(plan.getId());
        resp.setTitle(title);
        resp.setGoal(req.getGoal());
        resp.setGoalInterpreted(plan.getGoalInterpreted());
        resp.setDailyHours(req.getDailyHours());
        resp.setCurrentLevel(req.getCurrentLevel());
        resp.setTotalDays(totalDays);
        resp.setStatus("ACTIVE");
        resp.setProgress(0);
        resp.setDailyTasks(taskResponses);
        return resp;
    }

    /**
     * 获取计划详情（校验归属）
     */
    public StudyPlanResponse getPlan(Long planId, Long userId) {
        StudyPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BusinessException(404, "计划不存在");
        if (!plan.getUserId().equals(userId)) throw new BusinessException(403, "无权访问该计划");
        return toResponse(plan);
    }

    /** 内部/MCP 调用：不做用户归属校验 */
    public StudyPlanResponse getPlan(Long planId) {
        StudyPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BusinessException(404, "计划不存在");
        return toResponse(plan);
    }

    /**
     * 获取用户计划列表
     */
    public List<StudyPlanResponse> listByUser(Long userId) {
        var list = planMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudyPlan>()
                        .eq(StudyPlan::getUserId, userId)
                        .orderByDesc(StudyPlan::getCreatedAt));
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 更新计划状态
     */
    public void updateStatus(Long planId, Long userId, String status) {
        StudyPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BusinessException(404, "计划不存在");
        if (!plan.getUserId().equals(userId)) throw new BusinessException(403, "无权操作该计划");
        plan.setStatus(status);
        planMapper.updateById(plan);
        redisService.cachePlan(plan);
    }

    private StudyPlanResponse toResponse(StudyPlan plan) {
        StudyPlanResponse resp = new StudyPlanResponse();
        resp.setId(plan.getId());
        resp.setTitle(plan.getTitle());
        resp.setGoal(plan.getGoal());
        resp.setGoalInterpreted(plan.getGoalInterpreted());
        resp.setDailyHours(plan.getDailyHours());
        resp.setCurrentLevel(plan.getCurrentLevel());
        resp.setTotalDays(plan.getTotalDays());
        resp.setStatus(plan.getStatus());
        resp.setProgress(plan.getProgress());

        var tasks = taskMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DailyTask>()
                        .eq(DailyTask::getPlanId, plan.getId())
                        .orderByAsc(DailyTask::getDayIndex));
        resp.setDailyTasks(tasks.stream().map(t -> {
            DailyTaskResponse tr = new DailyTaskResponse();
            tr.setId(t.getId());
            tr.setDayIndex(t.getDayIndex());
            tr.setDate(t.getDate());
            tr.setTitle(t.getTitle());
            tr.setDescription(t.getDescription());
            tr.setEstimatedMinutes(t.getEstimatedMinutes());
            tr.setStatus(t.getStatus());
            tr.setCheckedIn(t.getCheckedIn());
            return tr;
        }).collect(Collectors.toList()));
        return resp;
    }

    private JsonNode extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                return objectMapper.readTree(raw.substring(start, end + 1));
            } catch (Exception e) {
                log.warn("Plan JSON parse failed: {}", e.getMessage());
            }
        }
        return objectMapper.createObjectNode();
    }
}

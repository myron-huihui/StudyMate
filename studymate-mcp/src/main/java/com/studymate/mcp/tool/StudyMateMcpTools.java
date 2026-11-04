package com.studymate.mcp.tool;

import com.studymate.ai.service.GoalUnderstandingService;
import com.studymate.ai.service.PlanGenerationService;
import com.studymate.api.service.PlanOrchestratorService;
import com.studymate.api.service.MaterialRecommendationService;
import com.studymate.api.service.TaskService;
import com.studymate.common.dto.CreatePlanRequest;
import com.studymate.common.dto.DailyTaskResponse;
import com.studymate.common.dto.MaterialRecommendResponse;
import com.studymate.common.dto.StudyPlanResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义：暴露 StudyMate 核心能力为 MCP tools
 */
@Component
@RequiredArgsConstructor
public class StudyMateMcpTools {

    private final PlanOrchestratorService planOrchestrator;
    private final TaskService taskService;
    private final MaterialRecommendationService recommendService;

    @Tool(name = "create_study_plan",
          description = "创建 AI 学习计划：根据学习目标、每日可用时间和当前基础，生成按天拆解的可执行学习计划")
    public StudyPlanResponse createStudyPlan(
            @ToolParam(description = "学习目标", required = true) String goal,
            @ToolParam(description = "每日可用时间（小时）", required = true) Double dailyHours,
            @ToolParam(description = "当前基础描述", required = true) String currentLevel,
            @ToolParam(description = "期望总天数（可选）", required = false) Integer totalDays) {
        CreatePlanRequest req = new CreatePlanRequest();
        req.setGoal(goal);
        req.setDailyHours(dailyHours);
        req.setCurrentLevel(currentLevel);
        req.setTotalDays(totalDays);
        return planOrchestrator.createPlan(1L, req);
    }

    @Tool(name = "get_plan", description = "获取学习计划详情，包含所有每日任务")
    public StudyPlanResponse getPlan(
            @ToolParam(description = "计划 ID", required = true) Long planId) {
        return planOrchestrator.getPlan(planId);
    }

    @Tool(name = "get_daily_task", description = "获取指定天的任务详情")
    public DailyTaskResponse getDailyTask(
            @ToolParam(description = "计划 ID", required = true) Long planId,
            @ToolParam(description = "第几天", required = true) int dayIndex) {
        return taskService.getByDay(planId, dayIndex);
    }

    @Tool(name = "check_in_task", description = "打卡完成任务")
    public String checkIn(
            @ToolParam(description = "任务 ID", required = true) Long taskId,
            @ToolParam(description = "用户 ID，默认 1", required = false) Long userId) {
        taskService.checkIn(taskId, userId != null ? userId : 1L);
        return "任务 " + taskId + " 打卡成功";
    }

    @Tool(name = "get_progress", description = "获取学习计划进度摘要")
    public Map<String, Object> getProgress(
            @ToolParam(description = "计划 ID", required = true) Long planId) {
        return taskService.getProgress(planId);
    }

    @Tool(name = "recommend_materials", description = "为学习目标或任务推荐学习资料")
    public List<MaterialRecommendResponse> recommendMaterials(
            @ToolParam(description = "学习目标或任务描述", required = true) String goal,
            @ToolParam(description = "推荐数量，默认5", required = false) Integer topK) {
        int k = topK != null ? topK : 5;
        return recommendService.recommend(goal, null, k);
    }
}

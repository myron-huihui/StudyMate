package com.studymate.api.controller;

import com.studymate.api.security.UserContext;
import com.studymate.api.service.TaskService;
import com.studymate.common.ApiResponse;
import com.studymate.common.dto.DailyTaskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 每日任务控制器
 */
@Tag(name = "每日任务", description = "任务查询、打卡、进度更新")
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @Operation(summary = "获取今日任务（Redis 缓存，写操作自动失效）")
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<DailyTaskResponse>>> today(@RequestParam Long planId) {
        return ResponseEntity.ok(ApiResponse.ok(
                taskService.todayTasks(planId, UserContext.currentUserId())));
    }

    @Operation(summary = "获取指定天的任务")
    @GetMapping("/day/{dayIndex}")
    public ResponseEntity<ApiResponse<DailyTaskResponse>> getByDay(
            @RequestParam Long planId,
            @PathVariable int dayIndex) {
        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getByDay(planId, dayIndex, UserContext.currentUserId())));
    }

    @Operation(summary = "获取计划全部任务")
    @GetMapping("/plan/{planId}")
    public ResponseEntity<ApiResponse<List<DailyTaskResponse>>> listByPlan(@PathVariable Long planId) {
        return ResponseEntity.ok(ApiResponse.ok(
                taskService.listByPlan(planId, UserContext.currentUserId())));
    }

    @Operation(summary = "打卡（幂等：重复调用不会重复计数）")
    @PostMapping("/{taskId}/checkin")
    public ResponseEntity<ApiResponse<Void>> checkIn(@PathVariable Long taskId) {
        taskService.checkIn(taskId, UserContext.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(summary = "更新任务状态")
    @PatchMapping("/{taskId}/status")
    public ResponseEntity<ApiResponse<Void>> updateStatus(
            @PathVariable Long taskId,
            @RequestParam String status) {
        taskService.updateStatus(taskId, UserContext.currentUserId(), status);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @Operation(summary = "获取进度摘要")
    @GetMapping("/progress/{planId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> progress(@PathVariable Long planId) {
        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getProgress(planId, UserContext.currentUserId())));
    }
}

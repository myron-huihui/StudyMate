package com.studymate.api.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.studymate.api.security.UserContext;
import com.studymate.api.service.PlanOrchestratorService;
import com.studymate.common.ApiResponse;
import com.studymate.common.dto.CreatePlanRequest;
import com.studymate.common.dto.StudyPlanResponse;
import com.studymate.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 学习计划控制器
 */
@Slf4j
@Tag(name = "学习计划", description = "创建、查询、更新学习计划")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class StudyPlanController {

    private final PlanOrchestratorService planOrchestrator;

    @Operation(summary = "创建学习计划（AI 生成）")
    @PostMapping
    @SentinelResource(value = "createStudyPlan",
            blockHandler = "createPlanBlocked",
            fallback = "createPlanFallback",
            exceptionsToIgnore = BusinessException.class)
    public ResponseEntity<ApiResponse<StudyPlanResponse>> createPlan(@Valid @RequestBody CreatePlanRequest req) {
        StudyPlanResponse plan = planOrchestrator.createPlan(UserContext.currentUserId(), req);
        return ResponseEntity.ok(ApiResponse.ok(plan));
    }

    /** 限流命中：明确提示稍后重试，不进入业务代码 */
    public ResponseEntity<ApiResponse<StudyPlanResponse>> createPlanBlocked(CreatePlanRequest req,
                                                                           BlockException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(429, "计划生成请求过于频繁，请稍后重试"));
    }

    /** 业务异常兜底：不把内部堆栈暴露给调用方 */
    public ResponseEntity<ApiResponse<StudyPlanResponse>> createPlanFallback(CreatePlanRequest req,
                                                                            Throwable ex) {
        log.error("Create plan fallback, goal={}", req.getGoal(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "计划生成服务暂时不可用，请稍后重试"));
    }

    @Operation(summary = "获取计划详情")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StudyPlanResponse>> getPlan(@PathVariable Long id) {
        StudyPlanResponse plan = planOrchestrator.getPlan(id, UserContext.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(plan));
    }

    @Operation(summary = "获取当前用户所有计划")
    @GetMapping
    public ResponseEntity<ApiResponse<List<StudyPlanResponse>>> listPlans() {
        List<StudyPlanResponse> plans = planOrchestrator.listByUser(UserContext.currentUserId());
        return ResponseEntity.ok(ApiResponse.ok(plans));
    }

    @Operation(summary = "更新计划状态")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Void>> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        planOrchestrator.updateStatus(id, UserContext.currentUserId(), status);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}

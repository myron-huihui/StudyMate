package com.studymate.api.controller;

import com.studymate.api.service.MaterialRecommendationService;
import com.studymate.common.ApiResponse;
import com.studymate.common.dto.MaterialRecommendResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 资料推荐控制器
 */
@Tag(name = "资料推荐", description = "基于 RAG 的学习资料推荐")
@RestController
@RequestMapping("/api/v1/materials")
@RequiredArgsConstructor
public class MaterialRecommendController {

    private final MaterialRecommendationService recommendService;

    @Operation(summary = "为学习目标推荐资料")
    @PostMapping("/recommend")
    public ResponseEntity<ApiResponse<List<MaterialRecommendResponse>>> recommend(
            @RequestParam String goal,
            @RequestParam(defaultValue = "5") int topK) {
        List<MaterialRecommendResponse> results = recommendService.recommend(goal, null, topK);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    @Operation(summary = "为指定任务推荐资料")
    @PostMapping("/recommend/task")
    public ResponseEntity<ApiResponse<List<MaterialRecommendResponse>>> recommendForTask(
            @RequestParam Long taskId,
            @RequestParam(defaultValue = "3") int topK) {
        List<MaterialRecommendResponse> results = recommendService.recommendForTask(taskId, topK);
        return ResponseEntity.ok(ApiResponse.ok(results));
    }
}

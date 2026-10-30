package com.studymate.api.service;

import com.studymate.ai.service.MaterialRecommendService;
import com.studymate.common.dto.MaterialRecommendResponse;
import com.studymate.common.entity.DailyTask;
import com.studymate.dal.mapper.DailyTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 资料推荐 API 服务（桥接 AI 层 → API 层）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialRecommendationService {

    private final MaterialRecommendService aiRecommendService;
    private final DailyTaskMapper taskMapper;

    public List<MaterialRecommendResponse> recommend(String goal, String currentTask, int topK) {
        return aiRecommendService.recommend(goal, currentTask, topK);
    }

    public List<MaterialRecommendResponse> recommendForTask(Long taskId, int topK) {
        DailyTask task = taskMapper.selectById(taskId);
        if (task == null) return List.of();
        return aiRecommendService.recommend(task.getTitle(), task.getDescription(), topK);
    }
}

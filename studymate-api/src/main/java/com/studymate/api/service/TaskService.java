package com.studymate.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.common.dto.DailyTaskResponse;
import com.studymate.common.entity.DailyTask;
import com.studymate.common.entity.StudyPlan;
import com.studymate.common.entity.TaskCheckin;
import com.studymate.common.exception.BusinessException;
import com.studymate.dal.mapper.DailyTaskMapper;
import com.studymate.dal.mapper.StudyPlanMapper;
import com.studymate.dal.mapper.TaskCheckinMapper;
import com.studymate.dal.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务服务：查询、幂等打卡、进度。
 * 打卡幂等 = Redis 快速去重 + 唯一键 (task_id,user_id) 数据库兜底；
 * 今日任务 = Redis TTL 缓存，任何写操作后删除缓存而非更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private static final Duration TODAY_CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration CHECKIN_MARK_TTL = Duration.ofDays(1);

    private final DailyTaskMapper taskMapper;
    private final StudyPlanMapper planMapper;
    private final TaskCheckinMapper checkinMapper;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    public DailyTaskResponse getByDay(Long planId, int dayIndex, Long userId) {
        requireOwnedPlan(planId, userId);
        return getByDay(planId, dayIndex);
    }

    public DailyTaskResponse getByDay(Long planId, int dayIndex) {
        DailyTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<DailyTask>()
                        .eq(DailyTask::getPlanId, planId)
                        .eq(DailyTask::getDayIndex, dayIndex)
                        .last("LIMIT 1"));
        if (task == null) throw new BusinessException(404, "第 " + dayIndex + " 天任务不存在");
        return toResponse(task);
    }

    public List<DailyTaskResponse> listByPlan(Long planId, Long userId) {
        requireOwnedPlan(planId, userId);
        return listByPlan(planId);
    }

    public List<DailyTaskResponse> listByPlan(Long planId) {
        List<DailyTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<DailyTask>()
                        .eq(DailyTask::getPlanId, planId)
                        .orderByAsc(DailyTask::getDayIndex));
        return tasks.stream().map(this::toResponse).toList();
    }

    /**
     * 今日任务：Redis 缓存优先，miss 回源并回填；写操作后删除缓存避免并发脏读
     */
    public List<DailyTaskResponse> todayTasks(Long planId, Long userId) {
        requireOwnedPlan(planId, userId);
        LocalDate today = LocalDate.now();
        String cached = redisService.getTodayTasks(userId, planId, today);
        if (cached != null) {
            return deserialize(cached);
        }
        List<DailyTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<DailyTask>()
                        .eq(DailyTask::getPlanId, planId)
                        .eq(DailyTask::getDate, today)
                        .orderByAsc(DailyTask::getDayIndex));
        List<DailyTaskResponse> result = tasks.stream().map(this::toResponse).toList();
        try {
            redisService.cacheTodayTasks(userId, planId, today,
                    objectMapper.writeValueAsString(result), TODAY_CACHE_TTL);
        } catch (Exception e) {
            log.debug("Cache today tasks failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 打卡：重复调用直接成功返回，不重复写任务与进度
     */
    public void checkIn(Long taskId, Long userId) {
        DailyTask task = requireOwnedTask(taskId, userId);
        if (Boolean.TRUE.equals(task.getCheckedIn())) {
            return;
        }
        if (!redisService.markCheckinIdempotent(taskId, userId, CHECKIN_MARK_TTL)) {
            log.info("Checkin already in flight or done, taskId={}, userId={}", taskId, userId);
            return;
        }
        try {
            TaskCheckin record = new TaskCheckin();
            record.setTaskId(taskId);
            record.setUserId(userId);
            record.setCreatedAt(LocalDateTime.now());
            checkinMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("Checkin already recorded by unique key, taskId={}, userId={}", taskId, userId);
            return;
        } catch (RuntimeException e) {
            redisService.clearCheckinMark(taskId, userId);
            throw e;
        }

        task.setCheckedIn(true);
        task.setCheckedAt(LocalDateTime.now());
        task.setStatus("DONE");
        taskMapper.updateById(task);
        redisService.recordHotTask(task.getPlanId(), taskId);
        evictTodayCache(task);
        updatePlanProgress(task.getPlanId());
    }

    public void updateStatus(Long taskId, Long userId, String status) {
        DailyTask task = requireOwnedTask(taskId, userId);
        task.setStatus(status);
        taskMapper.updateById(task);
        evictTodayCache(task);
        updatePlanProgress(task.getPlanId());
    }

    public Map<String, Object> getProgress(Long planId, Long userId) {
        requireOwnedPlan(planId, userId);
        return getProgress(planId);
    }

    public Map<String, Object> getProgress(Long planId) {
        List<DailyTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<DailyTask>().eq(DailyTask::getPlanId, planId));
        long total = tasks.size();
        long done = tasks.stream().filter(t -> "DONE".equals(t.getStatus())).count();
        long checked = tasks.stream().filter(t -> Boolean.TRUE.equals(t.getCheckedIn())).count();
        Map<String, Object> result = new HashMap<>();
        result.put("totalTasks", total);
        result.put("doneTasks", done);
        result.put("checkedIn", checked);
        result.put("progressPercent", total > 0 ? (int) (done * 100 / total) : 0);
        return result;
    }

    private void updatePlanProgress(Long planId) {
        List<DailyTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<DailyTask>().eq(DailyTask::getPlanId, planId));
        int done = (int) tasks.stream().filter(t -> "DONE".equals(t.getStatus())).count();
        StudyPlan plan = planMapper.selectById(planId);
        if (plan != null && plan.getTotalDays() != null && plan.getTotalDays() > 0) {
            plan.setProgress(Math.min(100, (int) (done * 100L / plan.getTotalDays())));
            planMapper.updateById(plan);
        }
    }

    private DailyTask requireOwnedTask(Long taskId, Long userId) {
        DailyTask task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(404, "任务不存在");
        StudyPlan plan = planMapper.selectById(task.getPlanId());
        if (plan == null) throw new BusinessException(404, "计划不存在");
        if (!plan.getUserId().equals(userId)) throw new BusinessException(403, "无权操作该任务");
        return task;
    }

    private StudyPlan requireOwnedPlan(Long planId, Long userId) {
        StudyPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BusinessException(404, "计划不存在");
        if (!plan.getUserId().equals(userId)) throw new BusinessException(403, "无权访问该计划");
        return plan;
    }

    private void evictTodayCache(DailyTask task) {
        if (task.getDate() == null) {
            return;
        }
        StudyPlan plan = planMapper.selectById(task.getPlanId());
        if (plan != null) {
            redisService.evictTodayTasks(plan.getUserId(), plan.getId(), task.getDate());
        }
    }

    private List<DailyTaskResponse> deserialize(String json) {
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, DailyTaskResponse.class);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Today task cache corrupted, fallback to DB: {}", e.getMessage());
            return List.of();
        }
    }

    private DailyTaskResponse toResponse(DailyTask t) {
        DailyTaskResponse r = new DailyTaskResponse();
        r.setId(t.getId());
        r.setDayIndex(t.getDayIndex());
        r.setDate(t.getDate());
        r.setTitle(t.getTitle());
        r.setDescription(t.getDescription());
        r.setEstimatedMinutes(t.getEstimatedMinutes());
        r.setStatus(t.getStatus());
        r.setCheckedIn(t.getCheckedIn());
        return r;
    }
}

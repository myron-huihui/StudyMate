package com.studymate.dal.redis;

import com.studymate.common.entity.StudyPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Redis 服务：模型短期记忆、热点数据、今日任务缓存、打卡幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final StringRedisTemplate redis;

    private static final String PLAN_KEY_PREFIX = "studymate:plan:";
    private static final String HOT_TASK_PREFIX = "studymate:hot:";
    private static final String SESSION_MEMORY_PREFIX = "studymate:memory:";
    private static final String MEMORY_OWNER_PREFIX = "studymate:memory:owner:";
    private static final String TODAY_TASK_PREFIX = "studymate:today:";
    private static final String CHECKIN_PREFIX = "studymate:checkin:";

    /** GETDEL 需要 Redis 6.2+，用 Lua 兼容旧版本并保持原子性 */
    private static final DefaultRedisScript<String> TAKE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) " +
            "if v then redis.call('DEL', KEYS[1]) end " +
            "return v", String.class);

    public void cachePlan(StudyPlan plan) {
        String key = PLAN_KEY_PREFIX + plan.getId();
        redis.opsForValue().set(key, plan.getTitle(), Duration.ofHours(2));
        log.debug("Cached plan: {}", key);
    }

    public String getCachedPlanTitle(Long planId) {
        return redis.opsForValue().get(PLAN_KEY_PREFIX + planId);
    }

    public void recordHotTask(Long planId, Long taskId) {
        String key = HOT_TASK_PREFIX + planId;
        redis.opsForSet().add(key, String.valueOf(taskId));
        redis.expire(key, Duration.ofDays(7));
    }

    public Set<String> getHotTasks(Long planId) {
        return redis.opsForSet().members(HOT_TASK_PREFIX + planId);
    }

    public void saveSessionMemory(String sessionId, String context, Duration ttl) {
        redis.opsForValue().set(SESSION_MEMORY_PREFIX + sessionId, context, ttl);
    }

    public String getSessionMemory(String sessionId) {
        return redis.opsForValue().get(SESSION_MEMORY_PREFIX + sessionId);
    }

    public void clearSessionMemory(String sessionId) {
        redis.delete(SESSION_MEMORY_PREFIX + sessionId);
    }

    // ---------- memoryId 隔离 + 滑动窗口 ----------

    public void bindMemoryOwner(String memoryId, Long userId, Duration ttl) {
        redis.opsForValue().setIfAbsent(MEMORY_OWNER_PREFIX + memoryId,
                String.valueOf(userId), ttl);
    }

    public Long getMemoryOwner(String memoryId) {
        String value = redis.opsForValue().get(MEMORY_OWNER_PREFIX + memoryId);
        return value != null ? Long.valueOf(value) : null;
    }

    public void pushMemoryTurn(String memoryId, String json, int maxTurns, Duration ttl) {
        String key = SESSION_MEMORY_PREFIX + memoryId;
        redis.opsForList().rightPush(key, json);
        redis.opsForList().trim(key, -maxTurns, -1);
        redis.expire(key, ttl);
    }

    public List<String> listMemoryTurns(String memoryId, int count) {
        String key = SESSION_MEMORY_PREFIX + memoryId;
        List<String> turns = redis.opsForList().range(key, -count, -1);
        return turns != null ? turns : List.of();
    }

    public void clearMemory(String memoryId) {
        redis.delete(SESSION_MEMORY_PREFIX + memoryId);
        redis.delete(MEMORY_OWNER_PREFIX + memoryId);
    }

    // ---------- 今日任务缓存 ----------

    public String getTodayTasks(Long userId, Long planId, LocalDate date) {
        return redis.opsForValue().get(todayKey(userId, planId, date));
    }

    public void cacheTodayTasks(Long userId, Long planId, LocalDate date, String json, Duration ttl) {
        redis.opsForValue().set(todayKey(userId, planId, date), json, ttl);
    }

    /** 写操作后删除而非更新，杜绝并发窗口内的脏读 */
    public void evictTodayTasks(Long userId, Long planId, LocalDate date) {
        redis.delete(todayKey(userId, planId, date));
    }

    // ---------- OAuth2 授权码 ----------

    public void saveAuthCode(String code, String json, Duration ttl) {
        redis.opsForValue().set("studymate:authcode:" + code, json, ttl);
    }

    /** 授权码一次性使用：取出即删除 */
    public String takeAuthCode(String code) {
        return redis.execute(TAKE_SCRIPT, List.of("studymate:authcode:" + code));
    }

    // ---------- 通用互斥锁（入库去重等） ----------

    public boolean tryLock(String key, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent("studymate:lock:" + key, "1", ttl));
    }

    public void unlock(String key) {
        redis.delete("studymate:lock:" + key);
    }

    // ---------- 打卡幂等 ----------

    public boolean markCheckinIdempotent(Long taskId, Long userId, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(CHECKIN_PREFIX + userId + ":" + taskId,
                "1", ttl);
        return Boolean.TRUE.equals(ok);
    }

    public void clearCheckinMark(Long taskId, Long userId) {
        redis.delete(CHECKIN_PREFIX + userId + ":" + taskId);
    }

    private String todayKey(Long userId, Long planId, LocalDate date) {
        return TODAY_TASK_PREFIX + userId + ":" + planId + ":" + date;
    }
}

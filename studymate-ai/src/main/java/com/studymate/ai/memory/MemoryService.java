package com.studymate.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmRequest;
import com.studymate.ai.llm.LlmResult;
import com.studymate.ai.llm.LlmTier;
import com.studymate.common.entity.ChatMemoryRecord;
import com.studymate.common.exception.BusinessException;
import com.studymate.common.trace.TraceContext;
import com.studymate.dal.mapper.ChatMemoryMapper;
import com.studymate.dal.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆层：memoryId 隔离 + Redis 最近 N 轮窗口 + MySQL 全量历史。
 * 消息以中立 role/content 存储，切换模型不丢上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private static final int WINDOW_TURNS = 12;
    private static final Duration TTL = Duration.ofHours(24);
    private static final String SYSTEM_PROMPT = "你是 StudyMate 学习助手，结合历史对话回答用户问题。";

    private final ChatMemoryMapper memoryMapper;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    private final LlmGateway llmGateway;

    public void append(String memoryId, Long userId, String role, String content, String model) {
        assertOwner(memoryId, userId);
        ChatMemoryRecord record = new ChatMemoryRecord();
        record.setMemoryId(memoryId);
        record.setUserId(userId);
        record.setRole(role);
        record.setContent(content);
        record.setModel(model);
        record.setCreatedAt(LocalDateTime.now());
        memoryMapper.insert(record);

        MemoryTurn turn = new MemoryTurn(role, content, model, record.getCreatedAt());
        try {
            redisService.pushMemoryTurn(memoryId, objectMapper.writeValueAsString(turn), WINDOW_TURNS, TTL);
        } catch (Exception e) {
            log.debug("Redis memory window write failed, MySQL history remains authoritative: {}", e.getMessage());
        }
    }

    /** 短期窗口：Redis 优先，miss 时回源 MySQL 并预热 */
    public List<MemoryTurn> window(String memoryId, Long userId) {
        assertOwner(memoryId, userId);
        try {
            List<String> cached = redisService.listMemoryTurns(memoryId, WINDOW_TURNS);
            if (!cached.isEmpty()) {
                List<MemoryTurn> turns = new ArrayList<>(cached.size());
                for (String json : cached) {
                    turns.add(objectMapper.readValue(json, MemoryTurn.class));
                }
                return turns;
            }
        } catch (Exception e) {
            log.debug("Redis memory window read failed: {}", e.getMessage());
        }
        List<MemoryTurn> fromDb = history(memoryId, userId, WINDOW_TURNS);
        warmUp(memoryId, fromDb);
        return fromDb;
    }

    public List<MemoryTurn> history(String memoryId, Long userId, int limit) {
        assertOwner(memoryId, userId);
        List<ChatMemoryRecord> records = memoryMapper.selectList(
                new LambdaQueryWrapper<ChatMemoryRecord>()
                        .eq(ChatMemoryRecord::getMemoryId, memoryId)
                        .orderByDesc(ChatMemoryRecord::getId)
                        .last("LIMIT " + Math.max(1, limit)));
        List<MemoryTurn> turns = new ArrayList<>(records.size());
        for (int i = records.size() - 1; i >= 0; i--) {
            ChatMemoryRecord r = records.get(i);
            turns.add(new MemoryTurn(r.getRole(), r.getContent(), r.getModel(), r.getCreatedAt()));
        }
        return turns;
    }

    public String contextPrompt(String memoryId, Long userId) {
        List<MemoryTurn> turns = window(memoryId, userId);
        StringBuilder sb = new StringBuilder();
        for (MemoryTurn turn : turns) {
            sb.append(turn.getRole()).append(": ").append(turn.getContent()).append('\n');
        }
        return sb.toString();
    }

    public MemoryReply chat(String memoryId, Long userId, String message, LlmTier tier, String modelName) {
        assertOwner(memoryId, userId);
        append(memoryId, userId, "user", message, null);

        String history = contextPrompt(memoryId, userId);
        String userPrompt = """
                历史对话：
                %s
                用户新消息：%s
                """.formatted(history, message);

        LlmResult result = llmGateway.call(LlmRequest.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(userPrompt)
                .tier(tier != null ? tier : LlmTier.BALANCED)
                .modelName(modelName)
                .memoryId(memoryId)
                .userId(userId)
                .build());

        append(memoryId, userId, "assistant", result.getContent(), result.getModel());

        return new MemoryReply(memoryId, result.getContent(), result.getModel(),
                String.valueOf(result.getFramework()), result.getTier(),
                result.totalTokens(), TraceContext.current());
    }

    /**
     * 流式对话：断连（取消）时 doOnComplete 不触发，助手回复不落库。
     */
    public Flux<String> streamChat(String memoryId, Long userId, String message, LlmTier tier, String modelName) {
        assertOwner(memoryId, userId);
        append(memoryId, userId, "user", message, null);

        String history = contextPrompt(memoryId, userId);
        String userPrompt = """
                历史对话：
                %s
                用户新消息：%s
                """.formatted(history, message);

        StringBuilder buffer = new StringBuilder();
        return llmGateway.stream(SYSTEM_PROMPT, userPrompt)
                .doOnNext(buffer::append)
                .doOnComplete(() -> {
                    if (!buffer.isEmpty()) {
                        append(memoryId, userId, "assistant", buffer.toString(), modelName);
                    }
                });
    }

    public void clear(String memoryId, Long userId) {
        assertOwner(memoryId, userId);
        memoryMapper.delete(new LambdaQueryWrapper<ChatMemoryRecord>()
                .eq(ChatMemoryRecord::getMemoryId, memoryId));
        redisService.clearMemory(memoryId);
    }

    private void warmUp(String memoryId, List<MemoryTurn> turns) {
        for (MemoryTurn turn : turns) {
            try {
                redisService.pushMemoryTurn(memoryId, objectMapper.writeValueAsString(turn), WINDOW_TURNS, TTL);
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private void assertOwner(String memoryId, Long userId) {
        if (memoryId == null || memoryId.isBlank()) {
            throw new BusinessException(400, "memoryId 不能为空");
        }
        Long owner = redisService.getMemoryOwner(memoryId);
        if (owner == null) {
            ChatMemoryRecord any = memoryMapper.selectOne(
                    new LambdaQueryWrapper<ChatMemoryRecord>()
                            .eq(ChatMemoryRecord::getMemoryId, memoryId)
                            .last("LIMIT 1"));
            if (any != null) {
                if (!any.getUserId().equals(userId)) {
                    throw new BusinessException(403, "无权访问该记忆空间");
                }
                owner = any.getUserId();
            } else {
                owner = userId;
            }
            redisService.bindMemoryOwner(memoryId, owner, TTL);
        }
        if (!owner.equals(userId)) {
            throw new BusinessException(403, "无权访问该记忆空间");
        }
    }

    public record MemoryReply(String memoryId, String content, String model, String framework,
                              LlmTier tier, int totalTokens, String traceId) {
    }
}

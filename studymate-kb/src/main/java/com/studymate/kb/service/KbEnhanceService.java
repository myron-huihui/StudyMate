package com.studymate.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmTier;
import com.studymate.kb.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 元数据增强：按批把父块交给 LLM 生成标题/摘要/关键词/难度。
 * LLM 失败按批降级为确定性元数据，不阻塞整条流水线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbEnhanceService {

    private static final String SYSTEM_PROMPT = """
            你是知识库元数据生成器。对给定的每段学习资料生成元数据，输出严格 JSON 数组，顺序与输入一致：
            [
              {"title": "不超过20字的标题", "summary": "不超过80字的摘要",
               "keywords": ["关键词1", "关键词2"], "difficulty": 1-5}
            ]
            只输出 JSON，不要 markdown 包裹，不要额外解释。
            """;

    private final LlmGateway llmGateway;
    private final KbProperties kbProperties;
    private final ObjectMapper objectMapper;

    public List<ParentMeta> enhance(List<String> parents, String fallbackTitle) {
        List<ParentMeta> metas = new ArrayList<>(parents.size());
        int batchSize = Math.max(1, kbProperties.getEnhanceBatchSize());
        for (int i = 0; i < parents.size(); i += batchSize) {
            List<String> batch = parents.subList(i, Math.min(i + batchSize, parents.size()));
            metas.addAll(enhanceBatch(batch, fallbackTitle));
        }
        return metas;
    }

    private List<ParentMeta> enhanceBatch(List<String> batch, String fallbackTitle) {
        try {
            String userPrompt = buildPrompt(batch);
            String raw = llmGateway.chat(SYSTEM_PROMPT, userPrompt, LlmTier.FAST);
            List<ParentMeta> parsed = parse(raw);
            List<ParentMeta> result = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                ParentMeta meta = i < parsed.size() ? parsed.get(i) : null;
                result.add(normalize(meta, fallbackTitle, batch.get(i)));
            }
            return result;
        } catch (Exception e) {
            log.warn("KB metadata enhance degraded for batch of {}: {}", batch.size(), e.getMessage());
            List<ParentMeta> fallback = new ArrayList<>(batch.size());
            for (String text : batch) {
                fallback.add(ParentMeta.fallback(fallbackTitle, text));
            }
            return fallback;
        }
    }

    private String buildPrompt(List<String> batch) {
        StringBuilder sb = new StringBuilder("请为以下资料生成元数据：\n");
        for (int i = 0; i < batch.size(); i++) {
            String text = batch.get(i);
            if (text.length() > 800) {
                text = text.substring(0, 800);
            }
            sb.append("资料").append(i + 1).append("：\n").append(text).append("\n\n");
        }
        return sb.toString();
    }

    private List<ParentMeta> parse(String raw) throws Exception {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("enhance output is not json array");
        }
        return objectMapper.readValue(raw.substring(start, end + 1),
                objectMapper.getTypeFactory().constructCollectionType(List.class, ParentMeta.class));
    }

    private ParentMeta normalize(ParentMeta meta, String fallbackTitle, String parentText) {
        if (meta == null) {
            return ParentMeta.fallback(fallbackTitle, parentText);
        }
        String title = meta.title() == null || meta.title().isBlank() ? fallbackTitle : meta.title();
        String summary = meta.summary() == null || meta.summary().isBlank()
                ? ParentMeta.fallback(fallbackTitle, parentText).summary()
                : meta.summary();
        int difficulty = Math.max(1, Math.min(5, meta.difficulty() == 0 ? 3 : meta.difficulty()));
        List<String> keywords = meta.keywords() == null ? List.of() : meta.keywords();
        return new ParentMeta(title, summary, keywords, difficulty);
    }
}

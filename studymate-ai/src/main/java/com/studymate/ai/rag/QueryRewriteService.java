package com.studymate.ai.rag;

import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmRequest;
import com.studymate.ai.llm.LlmTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Query 改写：补全省略指代、扩展同义词，提升双路召回覆盖率。
 * LLM 失败时退回规则改写，不阻塞检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final LlmGateway llmGateway;

    private static final String SYSTEM_PROMPT = """
            你是检索查询改写器。把用户问题改写为适合 BM25 与向量检索的中文查询语句。
            只输出改写后的查询本身，不要解释，不要加引号。
            要求：补全指代、保留专有名词、补充 2-3 个同义词，长度不超过 80 字。
            """;

    public String rewrite(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            String rewritten = llmGateway.call(LlmRequest.builder()
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(query)
                    .tier(LlmTier.FAST)
                    .capability(com.studymate.ai.llm.LlmCapability.CHAT)
                    .timeoutMs(3_000)
                    .maxTokens(200)
                    .build()).getContent();
            String cleaned = rewritten == null ? "" : rewritten.trim().replace("\n", " ");
            if (!cleaned.isBlank() && cleaned.length() <= 200) {
                return cleaned;
            }
        } catch (Exception e) {
            log.debug("Query rewrite degraded to rules: {}", e.getMessage());
        }
        return ruleRewrite(query);
    }

    private String ruleRewrite(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : query.split("[\\s,，。；;、]+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return String.join(" ", tokens);
    }
}

package com.studymate.ai.rag;

import com.studymate.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文压缩：按查询相关度挑选句子、去重、按预算裁剪，降低无效 token。
 */
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    private final AiProperties aiProperties;

    public String compress(String query, List<RagResult> results) {
        int budget = aiProperties.getRag().getContextBudget();
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();

        for (RagResult result : results) {
            String source = result.getTitle() != null ? result.getTitle() : "知识库文档";
            String body = result.getParentContent() != null && !result.getParentContent().isBlank()
                    ? result.getParentContent()
                    : result.getContent();
            if (body == null || body.isBlank()) {
                continue;
            }
            String compressed = selectSentences(query, body, Math.min(800, budget / 2));
            String key = compressed.length() > 80 ? compressed.substring(0, 80) : compressed;
            if (!seen.add(key)) {
                continue;
            }
            String block = String.format("[来源: %s | 层级: %s]\n%s\n---\n",
                    source, result.getRerankLevel(), compressed);
            if (sb.length() + block.length() > budget) {
                int remaining = budget - sb.length();
                if (remaining > 120) {
                    sb.append(block, 0, Math.min(block.length(), remaining));
                }
                break;
            }
            sb.append(block);
        }
        return sb.toString();
    }

    private String selectSentences(String query, String text, int maxChars) {
        String[] sentences = text.split("(?<=[。！？.!?\\n])");
        List<String> terms = terms(query);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i].trim();
            if (sentence.isEmpty()) {
                continue;
            }
            double score = terms.stream().filter(sentence::contains).count();
            scored.add(new Scored(sentence, score, i));
        }
        scored.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            return cmp != 0 ? cmp : Integer.compare(a.order, b.order);
        });
        StringBuilder sb = new StringBuilder();
        for (Scored s : scored) {
            if (sb.length() + s.text.length() > maxChars && sb.length() > 0) {
                break;
            }
            sb.append(s.text);
        }
        return sb.length() > 0 ? sb.toString() : text.substring(0, Math.min(text.length(), maxChars));
    }

    private List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) {
            return terms;
        }
        for (String token : query.split("[\\s,，。；;、:：/]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private record Scored(String text, double score, int order) {
    }
}

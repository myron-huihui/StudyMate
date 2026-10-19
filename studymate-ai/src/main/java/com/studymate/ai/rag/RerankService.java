package com.studymate.ai.rag;

import com.studymate.ai.config.AiProperties;
import com.studymate.ai.llm.LlmMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 精排三级降级：远端 gte-rerank → 本地词面重排 → 保留 RRF 原始顺序。
 * 任一级失败都不抛异常，主链路永远拿得到结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {

    private final GteRerankClient gteRerankClient;
    private final AiProperties aiProperties;
    private final LlmMetrics metrics;

    public record Outcome(List<RagResult> results, String level) {
    }

    public Outcome rerank(String query, List<RagResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new Outcome(List.of(), "RRF");
        }

        // 第一级：远端精排
        if (gteRerankClient.available()) {
            List<String> docs = candidates.stream()
                    .map(r -> r.getContent() != null ? r.getContent() : "")
                    .toList();
            List<GteRerankClient.Score> remote = gteRerankClient.rerank(query, docs, topK);
            if (remote != null && !remote.isEmpty()) {
                List<RagResult> ranked = new ArrayList<>();
                for (GteRerankClient.Score score : remote) {
                    if (score.index() >= 0 && score.index() < candidates.size()) {
                        RagResult copy = candidates.get(score.index());
                        copy.setRerankScore(score.score());
                        copy.setRerankLevel("REMOTE");
                        ranked.add(copy);
                    }
                }
                if (!ranked.isEmpty()) {
                    return new Outcome(ranked.stream().limit(topK).toList(), "REMOTE");
                }
            }
        }

        // 第二级：本地词面重排
        try {
            List<RagResult> local = new ArrayList<>(candidates);
            local.forEach(r -> r.setRerankScore(localScore(query, r)));
            local.sort(Comparator.comparingDouble((RagResult r) ->
                    r.getRerankScore() != null ? r.getRerankScore() : 0).reversed());
            local.forEach(r -> r.setRerankLevel("LOCAL"));
            return new Outcome(local.stream().limit(topK).toList(), "LOCAL");
        } catch (Exception e) {
            log.warn("Local rerank failed, keep RRF order: {}", e.getMessage());
            metrics.recordDegrade("rerank", "RRF");
        }

        // 第三级：RRF 原序
        candidates.forEach(r -> r.setRerankLevel("RRF"));
        int limit = Math.min(topK, candidates.size());
        return new Outcome(candidates.subList(0, limit), "RRF");
    }

    private double localScore(String query, RagResult result) {
        String content = result.getContent() != null ? result.getContent() : "";
        String title = result.getTitle() != null ? result.getTitle() : "";
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return result.getRrfScore() != null ? result.getRrfScore() : 0;
        }
        int hit = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                hit++;
            }
        }
        double coverage = (double) hit / terms.size();
        double titleBoost = terms.stream().anyMatch(title::contains) ? 0.2 : 0;
        double rrf = result.getRrfScore() != null ? Math.min(0.2, result.getRrfScore()) : 0;
        return coverage * 0.6 + titleBoost + rrf;
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
        // 中文按二元组补充
        String compact = query.replaceAll("[\\s\\p{Punct}，。；：、]", "");
        for (int i = 0; i + 2 <= compact.length(); i++) {
            terms.add(compact.substring(i, i + 2));
        }
        return terms;
    }
}

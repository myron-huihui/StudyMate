package com.studymate.ai.rag;

import com.studymate.ai.config.AiProperties;
import com.studymate.ai.llm.LlmGateway;
import com.studymate.ai.llm.LlmMetrics;
import com.studymate.common.trace.TraceContext;
import com.studymate.dal.elasticsearch.EsSearchService;
import com.studymate.dal.elasticsearch.EsSearchService.EsHit;
import com.studymate.dal.vector.PgVectorService;
import com.studymate.dal.vector.PgVectorService.VectorSearchResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RAG 混合检索：
 * Query 改写 → pgvector 向量召回 + ES BM25 并行（单路超时丢弃）→ RRF 融合 → 精排三级降级 → 上下文压缩。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagHybridSearchService {

    private static final int RRF_K = 60;

    private final LlmGateway llmGateway;
    private final PgVectorService pgVectorService;
    private final EsSearchService esSearchService;
    private final QueryRewriteService queryRewriteService;
    private final RerankService rerankService;
    private final ContextCompressor contextCompressor;
    private final AiProperties aiProperties;
    private final LlmMetrics metrics;

    private final ExecutorService recallPool = new ThreadPoolExecutor(
            4, 12, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            new ThreadPoolExecutor.CallerRunsPolicy());

    public List<RagResult> hybridSearch(String query, int topK) {
        int finalTopK = topK > 0 ? topK : aiProperties.getRagTopK();
        String rewritten = aiProperties.getRag().isRewriteEnabled()
                ? queryRewriteService.rewrite(query)
                : query;
        String recallQuery = rewritten != null && !rewritten.isBlank() ? rewritten : query;
        long timeout = aiProperties.getRag().getRouteTimeoutMs();
        int recallTopK = aiProperties.getRag().getRecallTopK();

        CompletableFuture<List<VectorSearchResult>> vectorFuture = CompletableFuture.supplyAsync(
                TraceContext.wrap(() -> {
                    float[] vector = llmGateway.embed(recallQuery);
                    return pgVectorService.searchByVector(vector, recallTopK);
                }), recallPool);

        CompletableFuture<List<EsHit>> esFuture = CompletableFuture.supplyAsync(
                TraceContext.wrap(() -> esSearchService.bm25Search(recallQuery, recallTopK)), recallPool);

        List<VectorSearchResult> vectorHits = await(vectorFuture, timeout, "vector");
        List<EsHit> esHits = await(esFuture, timeout, "bm25");

        List<RagResult> fused = rrfFusion(vectorHits, esHits);
        int rerankTopN = Math.min(aiProperties.getRag().getRerankTopK(), fused.size());
        List<RagResult> candidates = fused.subList(0, rerankTopN);
        return rerankService.rerank(recallQuery, candidates, finalTopK).results();
    }

    public String buildContext(String query, int topK) {
        List<RagResult> results = hybridSearch(query, topK);
        return contextCompressor.compress(query, results);
    }

    private <T> List<T> await(CompletableFuture<List<T>> future, long timeoutMs, String route) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            metrics.recordDegrade("rag." + route, "TIMEOUT_OR_ERROR");
            log.warn("RAG route [{}] degraded: {}", route, e.getMessage());
            return List.of();
        }
    }

    private List<RagResult> rrfFusion(List<VectorSearchResult> vectorHits, List<EsHit> esHits) {
        Map<String, RagResult> fused = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            VectorSearchResult hit = vectorHits.get(i);
            RagResult result = fused.computeIfAbsent(key(hit.getMaterialId(), hit.getChunkIndex()),
                    k -> new RagResult());
            result.setMaterialId(hit.getMaterialId());
            result.setChunkIndex(hit.getChunkIndex());
            result.setParentId(hit.getParentId());
            result.setParentContent(hit.getParentContent());
            result.setContent(hit.getContent());
            result.setVectorScore(hit.getScore());
            result.setRrfScore(defaultScore(result.getRrfScore()) + 1.0 / (RRF_K + i + 1));
            result.setSource(result.getBm25Score() != null ? "both" : "vector");
        }

        for (int i = 0; i < esHits.size(); i++) {
            EsHit hit = esHits.get(i);
            String key = key(hit.getMaterialId(), hit.getChunkIndex());
            RagResult result = fused.computeIfAbsent(key, k -> new RagResult());
            result.setMaterialId(hit.getMaterialId());
            result.setChunkIndex(hit.getChunkIndex());
            result.setParentId(hit.getParentId());
            result.setTitle(hit.getTitle());
            result.setCategory(hit.getCategory());
            result.setParentContent(hit.getParentContent());
            if (result.getContent() == null || result.getContent().isBlank()) {
                result.setContent(hit.getContent());
            }
            result.setBm25Score(hit.getScore());
            result.setRrfScore(defaultScore(result.getRrfScore()) + 1.0 / (RRF_K + i + 1));
            result.setSource(result.getVectorScore() != null ? "both" : "bm25");
        }

        return new ArrayList<>(fused.values().stream()
                .sorted(Comparator.comparingDouble((RagResult r) -> defaultScore(r.getRrfScore())).reversed())
                .toList());
    }

    private String key(Long materialId, Integer chunkIndex) {
        return (materialId != null ? materialId : -1) + ":" + (chunkIndex != null ? chunkIndex : 0);
    }

    private double defaultScore(Double score) {
        return score != null ? score : 0;
    }

    @PreDestroy
    void shutdown() {
        recallPool.shutdownNow();
    }
}

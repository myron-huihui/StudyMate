package com.studymate.dal.elasticsearch;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ES BM25 全文召回。
 * 只依赖 ElasticsearchOperations（不建 Repository），ES 未启动时应用仍能正常启动，
 * 检索/写入失败仅记录告警，由上层按"单路降级"处理，不阻断主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsSearchService {

    private final ElasticsearchOperations esOperations;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    /** BM25 召回（title 加权），返回带原生分数的命中 */
    public List<EsHit> bm25Search(String query, int topK) {
        ensureIndex();
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .should(s -> s.match(m -> m.field("content").query(query)))
                        .should(s -> s.match(m -> m.field("title").query(query).boost(2.0f)))
                        .minimumShouldMatch("1")))
                .withPageable(PageRequest.of(0, topK))
                .build();
        SearchHits<MaterialEsDocument> hits = esOperations.search(nativeQuery, MaterialEsDocument.class);
        return hits.getSearchHits().stream()
                .map(this::toHit)
                .toList();
    }

    public List<MaterialEsDocument> fullTextSearch(String query, int topK) {
        return bm25Search(query, topK).stream().map(EsHit::getDocument).toList();
    }

    public List<MaterialEsDocument> searchByTitle(String keyword, int topK) {
        ensureIndex();
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("title").query(keyword)))
                .withPageable(PageRequest.of(0, topK))
                .build();
        SearchHits<MaterialEsDocument> hits = esOperations.search(query, MaterialEsDocument.class);
        return hits.getSearchHits().stream().map(SearchHit::getContent).toList();
    }

    public MaterialEsDocument index(MaterialEsDocument doc) {
        try {
            ensureIndex();
            return esOperations.save(doc);
        } catch (Exception e) {
            log.warn("ES index failed (degraded): materialId={}, {}", doc.getMaterialId(), e.getMessage());
            return doc;
        }
    }

    public void deleteByMaterialId(Long materialId) {
        try {
            ensureIndex();
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.term(t -> t.field("materialId").value(materialId)))
                    .build();
            esOperations.delete(query, MaterialEsDocument.class);
        } catch (Exception e) {
            log.warn("ES delete failed (degraded): materialId={}, {}", materialId, e.getMessage());
        }
    }

    public void deleteById(String id) {
        try {
            esOperations.delete(id, MaterialEsDocument.class);
        } catch (Exception e) {
            log.warn("ES delete failed (degraded): id={}, {}", id, e.getMessage());
        }
    }

    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        try {
            IndexOperations ops = esOperations.indexOps(MaterialEsDocument.class);
            if (!ops.exists()) {
                ops.createWithMapping();
            }
            indexReady.set(true);
        } catch (Exception e) {
            log.warn("ES index not ready (will retry later): {}", e.getMessage());
        }
    }

    private EsHit toHit(SearchHit<MaterialEsDocument> hit) {
        MaterialEsDocument doc = hit.getContent();
        return EsHit.builder()
                .materialId(doc.getMaterialId())
                .chunkIndex(doc.getChunkIndex())
                .parentId(doc.getParentId())
                .content(doc.getContent())
                .parentContent(doc.getParentContent())
                .title(doc.getTitle())
                .category(doc.getCategory())
                .score((double) hit.getScore())
                .document(doc)
                .build();
    }

    @Data
    @Builder
    public static class EsHit {
        private Long materialId;
        private Integer chunkIndex;
        private String parentId;
        private String content;
        private String parentContent;
        private String title;
        private String category;
        private Double score;
        private MaterialEsDocument document;
    }
}

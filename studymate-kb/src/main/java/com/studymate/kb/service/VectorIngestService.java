package com.studymate.kb.service;

import com.studymate.ai.llm.LlmGateway;
import com.studymate.dal.elasticsearch.EsSearchService;
import com.studymate.dal.elasticsearch.MaterialEsDocument;
import com.studymate.dal.vector.PgVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Embed / Store 两阶段的落地实现：子块向量写 pgVector，父子结构写 ES。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIngestService {

    private final LlmGateway llmGateway;
    private final PgVectorService pgVectorService;
    private final EsSearchService esSearchService;

    /** EMBED：先清旧向量再重新写入，保证断点重跑幂等 */
    public int embed(Long materialId, List<ChunkService.ChildChunk> chunks, String metaJson) {
        pgVectorService.deleteByMaterialId(materialId);
        for (ChunkService.ChildChunk chunk : chunks) {
            float[] vector = llmGateway.embed(chunk.content());
            pgVectorService.insertEmbedding(materialId, chunk.index(), chunk.parentIndex(),
                    chunk.parentContent(), chunk.content(), vector, metaJson != null ? metaJson : "{}");
        }
        log.info("Material {} embedded: {} child chunks", materialId, chunks.size());
        return chunks.size();
    }

    /** STORE：每个子块一条 ES 文档，携带父块内容用于上下文补全 */
    public int storeToEs(Long materialId, String category, List<ChunkService.ChildChunk> chunks,
                         List<ParentMeta> metas) {
        esSearchService.deleteByMaterialId(materialId);
        for (ChunkService.ChildChunk chunk : chunks) {
            ParentMeta meta = metas.get(Math.min(chunk.parentIndex(), Math.max(0, metas.size() - 1)));
            MaterialEsDocument doc = new MaterialEsDocument();
            doc.setId(materialId + "_" + chunk.index());
            doc.setMaterialId(materialId);
            doc.setChunkIndex(chunk.index());
            doc.setParentId(String.valueOf(chunk.parentIndex()));
            doc.setParentContent(chunk.parentContent());
            doc.setContent(chunk.content());
            doc.setTitle(meta.title());
            doc.setSummary(meta.summary());
            doc.setCategory(category);
            doc.setDifficulty(meta.difficulty());
            doc.setCreatedAt(LocalDateTime.now());
            esSearchService.index(doc);
        }
        log.info("Material {} stored to ES: {} child chunks", materialId, chunks.size());
        return chunks.size();
    }
}

package com.studymate.dal.vector;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * pgVector 向量检索服务
 * 使用 PostgreSQL + pgvector 扩展存储和检索向量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgVectorService {

    /** 使用独立的 pgVector JdbcTemplate（在配置类中定义） */
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 首次使用时建表，失败不阻断启动，后续调用自动重试 */
    private void ensureInitialized() {
        if (initialized.get()) {
            return;
        }
        synchronized (this) {
            if (initialized.get()) {
                return;
            }
            try {
                initTable();
                initialized.set(true);
            } catch (Exception e) {
                log.warn("pgVector init deferred: {}", e.getMessage());
            }
        }
    }

    /**
     * 初始化向量表（启动时调用，幂等）
     */
    public void initTable() {
        pgVectorJdbcTemplate.execute("""
            CREATE EXTENSION IF NOT EXISTS vector;
            CREATE TABLE IF NOT EXISTS t_document_embedding (
                id BIGSERIAL PRIMARY KEY,
                material_id BIGINT NOT NULL,
                chunk_index INT NOT NULL DEFAULT 0,
                parent_id TEXT,
                parent_content TEXT,
                content TEXT NOT NULL,
                embedding vector(1024),
                meta JSONB,
                created_at TIMESTAMP DEFAULT NOW()
            );
            ALTER TABLE t_document_embedding ADD COLUMN IF NOT EXISTS parent_id TEXT;
            ALTER TABLE t_document_embedding ADD COLUMN IF NOT EXISTS parent_content TEXT;
            CREATE INDEX IF NOT EXISTS idx_embedding ON t_document_embedding
                USING hnsw (embedding vector_cosine_ops);
            CREATE INDEX IF NOT EXISTS idx_material ON t_document_embedding(material_id);
        """);
        log.info("pgVector table initialized");
    }

    /**
     * 插入文档向量
     */
    public void insertEmbedding(Long materialId, int chunkIndex, String content, float[] vector, String metaJson) {
        insertEmbedding(materialId, chunkIndex, null, null, content, vector, metaJson);
    }

    public void insertEmbedding(Long materialId, int chunkIndex, Integer parentIndex, String parentContent,
                                String content, float[] vector, String metaJson) {
        ensureInitialized();
        String sql = """
            INSERT INTO t_document_embedding
                (material_id, chunk_index, parent_id, parent_content, content, embedding, meta)
            VALUES (?, ?, ?, ?, ?, ?::vector, ?::jsonb)
        """;
        pgVectorJdbcTemplate.update(sql, materialId, chunkIndex,
                parentIndex == null ? null : String.valueOf(parentIndex),
                parentContent, content, vectorToString(vector), metaJson);
    }

    /**
     * 向量相似度搜索（余弦距离）
     */
    public List<VectorSearchResult> searchByVector(float[] queryVector, int topK) {
        ensureInitialized();
        String sql = """
            SELECT material_id, chunk_index, parent_id, parent_content, content, meta,
                   1 - (embedding <=> ?::vector) AS score
            FROM t_document_embedding
            ORDER BY embedding <=> ?::vector
            LIMIT ?
        """;
        String vecStr = vectorToString(queryVector);
        return pgVectorJdbcTemplate.query(sql, rs -> {
            List<VectorSearchResult> results = new java.util.ArrayList<>();
            while (rs.next()) {
                VectorSearchResult r = new VectorSearchResult();
                r.setMaterialId(rs.getLong("material_id"));
                r.setChunkIndex(rs.getInt("chunk_index"));
                r.setParentId(rs.getString("parent_id"));
                r.setParentContent(rs.getString("parent_content"));
                r.setContent(rs.getString("content"));
                r.setMeta(rs.getString("meta"));
                r.setScore(rs.getDouble("score"));
                results.add(r);
            }
            return results;
        }, vecStr, vecStr, topK);
    }

    /**
     * 按 materialId 删除向量
     */
    public void deleteByMaterialId(Long materialId) {
        ensureInitialized();
        pgVectorJdbcTemplate.update("DELETE FROM t_document_embedding WHERE material_id = ?", materialId);
    }

    /**
     * float[] 转 pgvector 字面量字符串
     */
    private String vectorToString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    @Data
    public static class VectorSearchResult {
        private Long materialId;
        private Integer chunkIndex;
        private String parentId;
        private String parentContent;
        private String content;
        private String meta;
        private Double score;
    }
}

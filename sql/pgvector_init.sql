-- StudyMate pgVector 初始化脚本
-- 在 PostgreSQL 中执行

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS t_document_embedding (
    id BIGSERIAL PRIMARY KEY,
    material_id BIGINT NOT NULL,
    chunk_index INT NOT NULL DEFAULT 0,
    parent_index INT DEFAULT 0,
    parent_content TEXT,
    content TEXT NOT NULL,
    embedding vector(1024),
    meta JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_embedding_hnsw
    ON t_document_embedding USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_embedding_material
    ON t_document_embedding(material_id);
CREATE INDEX IF NOT EXISTS idx_embedding_chunk
    ON t_document_embedding(material_id, chunk_index);

-- 示例：插入一条测试向量
-- INSERT INTO t_document_embedding (material_id, chunk_index, content, embedding, meta)
-- VALUES (1, 0, '测试内容', '[0.1, 0.2, ...]', '{"source": "test"}');

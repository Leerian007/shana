-- 知识库：文档 + 片段向量
--
-- 依赖 pgvector 扩展（https://github.com/pgvector/pgvector）。
-- CREATE EXTENSION 需要当前数据库用户具备相应权限（通常等同于超级用户）；
-- 官方镜像 pgvector/pgvector 自带扩展，只需这条语句即可启用。
-- 原生 PostgreSQL 需先安装扩展包，见 README「pgvector 安装」一节。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_document
(
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    source       VARCHAR(500),
    -- 正文的 SHA-256，用于上传幂等：同一份内容重复上传不重复向量化
    content_hash CHAR(64)     NOT NULL,
    chunk_count  INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE rag_document IS '知识库文档';
COMMENT ON COLUMN rag_document.content_hash IS '正文 SHA-256，唯一约束保证重复上传不会重复入库';

CREATE UNIQUE INDEX uk_rag_document_hash ON rag_document (content_hash);

CREATE TABLE rag_chunk
(
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT   NOT NULL REFERENCES rag_document (id) ON DELETE CASCADE,
    chunk_index INT      NOT NULL,
    content     TEXT     NOT NULL,
    -- 维度由 Flyway 占位符注入，与 shana.embedding.dimension 保持一致。
    -- 换嵌入模型（维度变化）时必须重建这张表，见 README 说明。
    embedding   vector(${embeddingDimension}) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE rag_chunk IS '知识库片段与向量';
COMMENT ON COLUMN rag_chunk.embedding IS '片段向量，维度由 shana.embedding.dimension 决定';

CREATE INDEX idx_rag_chunk_document ON rag_chunk (document_id, chunk_index);

-- HNSW + 余弦距离：适合边写边查的知识库。
-- IVFFlat 需要先有足够数据再建索引才有意义（聚类数依赖数据量），不适合从零开始的知识库。
CREATE INDEX idx_rag_chunk_embedding ON rag_chunk USING hnsw (embedding vector_cosine_ops);

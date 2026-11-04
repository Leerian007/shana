package com.shana.infrastructure.persistence;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.infrastructure.persistence.entity.ChunkSearchRow;
import com.shana.infrastructure.persistence.entity.RagDocumentEntity;
import com.shana.infrastructure.persistence.mapper.RagChunkMapper;
import com.shana.infrastructure.persistence.mapper.RagDocumentMapper;
import com.shana.rag.store.RetrievedChunk;
import com.shana.rag.store.SaveOutcome;
import com.shana.rag.store.VectorStore;
import com.shana.rag.store.VectorizedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 基于 PostgreSQL + pgvector 的向量存储。
 *
 * <p>跟着 {@code store=jdbc} 一起启用：知识库必须落库才有意义，
 * Redis 版上下文连历史都不保留，更不可能承载一份需要长期存在的资料库。
 *
 * <p>写入整体在一个事务里。中途失败（比如某条向量维度不匹配）会全部回滚，
 * 不会留下"文档记录有了、片段缺一半"的状态——那种数据在检索时表现为
 * "明明上传了却搜不到"，非常难排查。
 */
@Component
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "jdbc", matchIfMissing = true)
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final RagDocumentMapper documentMapper;
    private final RagChunkMapper chunkMapper;

    public PgVectorStore(RagDocumentMapper documentMapper, RagChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Override
    public Optional<Long> findDocumentIdByHash(String contentHash) {
        return Optional.ofNullable(documentMapper.selectIdByHash(contentHash));
    }

    @Override
    @Transactional
    public SaveOutcome saveDocument(String title, String source, String contentHash, List<VectorizedChunk> chunks) {
        RagDocumentEntity document = new RagDocumentEntity();
        document.setTitle(title);
        document.setSource(source);
        document.setContentHash(contentHash);
        document.setChunkCount(chunks.size());

        int inserted = documentMapper.insertIgnoreConflict(document);
        // ON CONFLICT DO NOTHING 不会回填主键，无论插入成功与否都要再查一次
        Long documentId = documentMapper.selectIdByHash(contentHash);
        if (documentId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "知识库文档写入后查询不到记录，contentHash=" + contentHash);
        }
        if (inserted == 0) {
            // 并发写入：别的实例抢先插入了同一份内容，本次不写片段，避免重复
            return new SaveOutcome(documentId, false);
        }

        for (VectorizedChunk chunk : chunks) {
            chunkMapper.insertVector(documentId, chunk.chunk().index(), chunk.chunk().content(),
                    VectorLiteral.format(chunk.vector()));
        }
        return new SaveOutcome(documentId, true);
    }

    @Override
    public List<RetrievedChunk> search(float[] queryVector, int topK, double minScore) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }

        List<ChunkSearchRow> rows = chunkMapper.searchByVector(VectorLiteral.format(queryVector), topK, minScore);
        return rows.stream()
                .map(row -> new RetrievedChunk(
                        row.getChunkId() == null ? 0L : row.getChunkId(),
                        row.getDocumentId() == null ? 0L : row.getDocumentId(),
                        row.getDocumentTitle(),
                        row.getContent(),
                        row.getScore() == null ? 0d : row.getScore()))
                .toList();
    }

    @Override
    public boolean deleteDocument(long documentId) {
        // chunk 通过外键 ON DELETE CASCADE 一起删掉，不用手动清理
        boolean deleted = documentMapper.deleteById(documentId) > 0;
        log.info("删除知识库文档: documentId={}, deleted={}", documentId, deleted);
        return deleted;
    }

    @Override
    public long countChunks() {
        return chunkMapper.countAll();
    }
}

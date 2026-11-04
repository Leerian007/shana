package com.shana.rag;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.EmbeddingClient;
import com.shana.rag.store.RetrievedChunk;
import com.shana.rag.store.SaveOutcome;
import com.shana.rag.store.VectorStore;
import com.shana.rag.store.VectorizedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 知识库默认实现：切分 → 向量化 → 落库；检索时反向。
 *
 * <p>{@link EmbeddingClient} 用 {@link ObjectProvider} 而不是构造参数：
 * 没配 {@code shana.embedding.api-key} 时容器里就没有这个 Bean，
 * 此时应用要能正常启动（问答、记忆都还在），只是调用知识库接口时明确报"未配置向量化模型"。
 * 把它做成必填依赖，会让一个可选能力拖垮整个启动流程。
 */
public class KnowledgeBaseImpl implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseImpl.class);

    private final VectorStore vectorStore;
    private final ObjectProvider<EmbeddingClient> embeddingClients;
    private final TextSplitter textSplitter;
    private final int defaultTopK;
    private final double minScore;

    public KnowledgeBaseImpl(VectorStore vectorStore,
                             ObjectProvider<EmbeddingClient> embeddingClients,
                             TextSplitter textSplitter,
                             int defaultTopK,
                             double minScore) {
        this.vectorStore = vectorStore;
        this.embeddingClients = embeddingClients;
        this.textSplitter = textSplitter;
        this.defaultTopK = defaultTopK;
        this.minScore = minScore;
    }

    @Override
    public IngestResult ingest(IngestCommand command) {
        String title = requireText(command.title(), "title");
        String content = requireText(command.content(), "content");
        // 先确认向量化能力可用，再做任何 IO：
        // 没配嵌入模型时入库注定失败，先查库只会把"缺配置"掩盖成数据库错误
        EmbeddingClient client = embedding();
        String contentHash = sha256(content);

        // 查重：命中就完全跳过向量化。这是最省钱的一步——
        // 一份 10 万字的文档重复上传会白烧几百次 embedding 调用
        Optional<Long> existing = vectorStore.findDocumentIdByHash(contentHash);
        if (existing.isPresent()) {
            log.info("知识库命中重复文档，跳过向量化: title={}, documentId={}", title, existing.get());
            return new IngestResult(existing.get(), 0, true);
        }

        List<String> blocks = textSplitter.split(content);
        if (blocks.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文切分后为空，请检查内容是否只有空白");
        }

        List<float[]> vectors = client.embed(blocks);
        if (vectors.size() != blocks.size()) {
            // 客户端已保证对应关系，这里再挡一次：错位的向量比没有向量危险得多
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "向量化结果与片段数不一致：" + vectors.size() + " vs " + blocks.size());
        }

        List<VectorizedChunk> chunks = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            chunks.add(new VectorizedChunk(new DocumentChunk(i, blocks.get(i)), vectors.get(i)));
        }

        SaveOutcome outcome = vectorStore.saveDocument(title, command.source(), contentHash, chunks);
        if (!outcome.created()) {
            // 竞态：查重时还没有，写入时已存在。按重复处理，不谎报切分数
            log.info("知识库文档在写入阶段被并发创建，本次未写入片段: documentId={}", outcome.documentId());
            return new IngestResult(outcome.documentId(), 0, true);
        }

        log.info("知识库文档已入库: documentId={}, title={}, chunks={}", outcome.documentId(), title, chunks.size());
        return new IngestResult(outcome.documentId(), chunks.size(), false);
    }

    @Override
    public List<RetrievedChunk> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = topK > 0 ? topK : defaultTopK;
        float[] queryVector = embedding().embed(List.of(query.strip())).getFirst();
        List<RetrievedChunk> chunks = vectorStore.search(queryVector, limit, minScore);
        log.debug("知识库检索: queryLength={}, topK={}, hits={}", query.length(), limit, chunks.size());
        return chunks;
    }

    @Override
    public boolean delete(long documentId) {
        return vectorStore.deleteDocument(documentId);
    }

    @Override
    public long countChunks() {
        return vectorStore.countChunks();
    }

    private EmbeddingClient embedding() {
        EmbeddingClient client = embeddingClients.getIfAvailable();
        if (client == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置向量化模型：请设置 shana.embedding.api-key、base-url 与 model");
        }
        return client;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        return value;
    }

    /** 内容指纹：用正文而不是标题，同一份资料换个标题上传也应该被挡住。 */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // 所有 JVM 都必须提供 SHA-256，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}

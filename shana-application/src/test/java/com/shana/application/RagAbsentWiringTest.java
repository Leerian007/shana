package com.shana.application;

import com.shana.core.chat.ChatService;
import com.shana.rag.KnowledgeBase;
import com.shana.rag.store.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配验证：非 jdbc 的上下文存储下，知识库整体不存在，但问答链路照常。
 *
 * <p>这不是"漏了实现"，而是刻意的：知识库必须落库，Redis / 内存模式连历史都不保留。
 * 验证"不存在"和验证"存在"同样重要——否则某天有人加了 {@code matchIfMissing = true}，
 * 就会在 Redis 模式下拿到一个指向不存在的表的知识库，运行时才炸。
 */
@ActiveProfiles("test")
@SpringBootTest
class RagAbsentWiringTest {

    @Autowired
    private ObjectProvider<KnowledgeBase> knowledgeBase;

    @Autowired
    private ObjectProvider<VectorStore> vectorStore;

    @Autowired
    private ChatService chatService;

    @Test
    @DisplayName("store=memory 时没有知识库，也没有向量存储")
    void shouldNotWireKnowledgeBaseForNonJdbcStore() {
        assertThat(knowledgeBase.getIfAvailable()).isNull();
        assertThat(vectorStore.getIfAvailable()).isNull();
    }

    @Test
    @DisplayName("没有知识库不影响问答服务装配")
    void shouldStillWireChatService() {
        assertThat(chatService).isNotNull();
    }
}

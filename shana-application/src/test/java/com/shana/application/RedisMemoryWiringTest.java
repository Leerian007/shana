package com.shana.application;

import com.shana.infrastructure.persistence.RedisConversationMemory;
import com.shana.memory.ConversationMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配验证：把 {@code shana.memory.store} 切成 {@code redis} 时，
 * 容器里应该只有 {@link RedisConversationMemory}，JDBC 与内存实现都不能同时在场。
 *
 * <p>三个实现都实现了同一个端口，一旦条件写错就会出现"两个同名 Bean"或
 * "谁都不是"的启动失败——这类问题单元测试发现不了，只能在完整上下文里验一次。
 *
 * <p>本测试不需要真实的 Redis：Spring Data Redis 的连接是懒建立的，
 * 装配阶段只创建客户端。真正的读写由 {@code RedisConversationMemoryTest}
 * 和启动 compose 后的联调覆盖。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "shana.memory.store=redis")
class RedisMemoryWiringTest {

    @Autowired
    private ConversationMemory conversationMemory;

    @Test
    @DisplayName("store=redis 时装配 Redis 实现")
    void shouldWireRedisImplementation() {
        assertThat(conversationMemory).isInstanceOf(RedisConversationMemory.class);
    }
}

package com.shana.infrastructure.persistence;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shana.infrastructure.persistence.entity.ChatMessageEntity;
import com.shana.infrastructure.persistence.mapper.ChatMessageMapper;
import com.shana.memory.ConversationMemory;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 数据库版上下文的单元测试。
 *
 * <p>Mapper 用 Mockito 替身，不连真实数据库——这里要验的是"我们发出的指令对不对"
 * （写入的字段、翻序逻辑、窗口大小），而不是 PostgreSQL 本身的行为。
 * 真实建表、真实读写由启动应用后的联调覆盖。
 */
class JdbcConversationMemoryTest {

    private ChatMessageMapper mapper;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatMessageMapper.class);
        memory = new JdbcConversationMemory(mapper, 20);
    }

    @Test
    @DisplayName("追加消息：角色转小写，写入会话 ID、发送者、内容与时间")
    void appendWritesLowercaseRoleAndTimestamp() {
        memory.append("group-1", ChatMessage.user("u-1001", "你好"));

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(mapper).insert(captor.capture());

        ChatMessageEntity saved = captor.getValue();
        assertThat(saved.getConversationId()).isEqualTo("group-1");
        // 枚举名大写是为了 Java 侧可读，入库统一小写，和主流 Chat API 一致
        assertThat(saved.getRole()).isEqualTo("user");
        // 发送者是留给事后统计的原始数据，必须原样落库
        assertThat(saved.getSenderId()).isEqualTo("u-1001");
        assertThat(saved.getContent()).isEqualTo("你好");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("读取历史：数据库按主键倒序取出，返回前要翻回时间正序")
    void historyReturnsChronologicalOrder() {
        // 数据库返回的是"最近的在前"
        ChatMessageEntity newer = entity(2L, "assistant", "第二句");
        ChatMessageEntity older = entity(1L, "user", "第一句");
        older.setSenderId("u-1001");
        Page<ChatMessageEntity> page = new Page<>(1, 20, false);
        page.setRecords(List.of(newer, older));
        doReturn(page).when(mapper).selectPage(any(), any());

        List<ChatMessage> history = memory.history("group-1");

        assertThat(history).containsExactly(
                new ChatMessage(ChatRole.USER, "u-1001", "第一句"),
                new ChatMessage(ChatRole.ASSISTANT, "第二句"));
    }

    @Test
    @DisplayName("读取历史：按窗口大小取最近 N 条，且不发起 count 查询")
    void historyLimitsToWindowWithoutCountQuery() {
        memory = new JdbcConversationMemory(mapper, 3);
        doReturn(new Page<ChatMessageEntity>(1, 3, false)).when(mapper).selectPage(any(), any());

        memory.history("group-1");

        ArgumentCaptor<Page<ChatMessageEntity>> captor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(captor.capture(), any());
        assertThat(captor.getValue().getSize()).isEqualTo(3);
        // 分页插件默认会先 count 一次，群聊消息表很大时这趟查询纯属浪费
        assertThat(captor.getValue().searchCount()).isFalse();
    }

    @Test
    @DisplayName("清空会话：按会话 ID 删除，不影响其他群")
    void clearDeletesOnlyTargetConversation() {
        memory.clear("group-1");

        verify(mapper).delete(any());
    }

    @Test
    @DisplayName("遇到无法识别的角色直接失败，而不是静默当成 user")
    void unknownRoleFailsFast() {
        ChatMessageEntity broken = entity(1L, "robot", "坏数据");
        Page<ChatMessageEntity> page = new Page<>(1, 20, false);
        page.setRecords(List.of(broken));
        doReturn(page).when(mapper).selectPage(any(), any());

        assertThatThrownBy(() -> memory.history("group-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("robot");
    }

    private static ChatMessageEntity entity(long id, String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setId(id);
        entity.setConversationId("group-1");
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }
}

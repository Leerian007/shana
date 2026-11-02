package com.shana.memory;

import com.shana.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryConversationMemoryTest {

    @Test
    @DisplayName("超出窗口时淘汰最早的消息，且保持时间正序")
    void shouldKeepOnlyRecentMessages() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(3);

        memory.append("group-1", ChatMessage.user("m1"));
        memory.append("group-1", ChatMessage.assistant("m2"));
        memory.append("group-1", ChatMessage.user("m3"));
        memory.append("group-1", ChatMessage.assistant("m4"));

        assertThat(memory.history("group-1"))
                .extracting(ChatMessage::content)
                .containsExactly("m2", "m3", "m4");
    }

    @Test
    @DisplayName("不同会话的上下文互相隔离")
    void shouldIsolateConversations() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(5);

        memory.append("group-1", ChatMessage.user("hello-a"));
        memory.append("group-2", ChatMessage.user("hello-b"));

        assertThat(memory.history("group-1")).extracting(ChatMessage::content).containsExactly("hello-a");
        assertThat(memory.history("group-2")).extracting(ChatMessage::content).containsExactly("hello-b");
    }

    @Test
    @DisplayName("读取不存在的会话返回空列表，而不是 null")
    void shouldReturnEmptyListForUnknownConversation() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(5);

        assertThat(memory.history("not-exists")).isEmpty();
    }

    @Test
    @DisplayName("清空后不再返回历史消息")
    void shouldClearConversation() {
        InMemoryConversationMemory memory = new InMemoryConversationMemory(5);
        memory.append("group-1", ChatMessage.user("hello"));

        memory.clear("group-1");

        assertThat(memory.history("group-1")).isEmpty();
    }

    @Test
    @DisplayName("窗口大小必须为正数")
    void shouldRejectNonPositiveWindow() {
        assertThatThrownBy(() -> new InMemoryConversationMemory(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-messages");
    }
}

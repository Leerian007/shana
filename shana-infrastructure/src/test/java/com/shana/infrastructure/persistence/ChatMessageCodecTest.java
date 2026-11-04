package com.shana.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消息编解码测试。
 *
 * <p>{@link ChatMessage} 是 record 且带一个额外的两参构造器，
 * 这里要确认 Jackson 走的是规范构造器、字段名没有丢，
 * 否则 Redis 里存进去再读出来会变成 senderId=null 而没人察觉。
 */
class ChatMessageCodecTest {

    private final ChatMessageCodec codec = new ChatMessageCodec(new ObjectMapper());

    @Test
    @DisplayName("带发送者的消息可以原样往返")
    void shouldRoundTripWithSender() {
        ChatMessage original = ChatMessage.user("u-1001", "你好");

        ChatMessage decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.senderId()).isEqualTo("u-1001");
        assertThat(decoded.role()).isEqualTo(ChatRole.USER);
    }

    @Test
    @DisplayName("没有发送者的消息往返后仍然是 null，不能变成空串")
    void shouldRoundTripWithoutSender() {
        ChatMessage decoded = codec.decode(codec.encode(ChatMessage.assistant("在的")));

        assertThat(decoded).isEqualTo(ChatMessage.assistant("在的"));
        assertThat(decoded.senderId()).isNull();
    }

    @Test
    @DisplayName("消息里的换行、引号、竖线不会破坏结构")
    void shouldKeepSpecialCharactersIntact() {
        String tricky = "第一行\n第二行 \"引号\" | 竖线 | 逗号, 冒号:";
        ChatMessage decoded = codec.decode(codec.encode(ChatMessage.user("u-1001", tricky)));

        assertThat(decoded.content()).isEqualTo(tricky);
    }

    @Test
    @DisplayName("解析失败直接抛出，而不是返回 null 让上下文悄悄少一条")
    void shouldFailFastOnBrokenPayload() {
        assertThatThrownBy(() -> codec.decode("{\"role\":\"USER\""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解析");
    }
}

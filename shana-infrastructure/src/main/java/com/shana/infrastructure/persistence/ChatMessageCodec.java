package com.shana.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.model.ChatMessage;

/**
 * {@link ChatMessage} 与 Redis 中存储的字符串之间的转换。
 *
 * <p>用 JSON 而不是 {@code role|senderId|content} 这类自定义分隔符：群聊消息里
 * 什么字符都可能出现（换行、竖线、引号），手写分隔符迟早会被一条特殊消息打穿。
 *
 * <p>特意不做"解析失败返回 null"的容错：Redis 里出现解析不了的值，说明写入端已经坏了
 * （版本不兼容或被外部篡改），静默跳过只会让上下文莫名少几条、且没人发现。
 * 与 {@code JdbcConversationMemory} 对未知 role 的处理保持一致——直接失败。
 *
 * <p>包级可见：只在 infrastructure 内部使用，不向上暴露。
 */
final class ChatMessageCodec {

    private final ObjectMapper objectMapper;

    ChatMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("消息序列化失败: " + message, e);
        }
    }

    ChatMessage decode(String json) {
        try {
            return objectMapper.readValue(json, ChatMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Redis 中存在无法解析的消息: " + json, e);
        }
    }
}

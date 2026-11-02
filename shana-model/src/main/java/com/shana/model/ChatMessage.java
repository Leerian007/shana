package com.shana.model;

import java.util.Objects;

/**
 * 一条对话消息。
 *
 * @param role    角色
 * @param content 消息内容
 */
public record ChatMessage(ChatRole role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content);
    }

    public static ChatMessage tool(String content) {
        return new ChatMessage(ChatRole.TOOL, content);
    }
}

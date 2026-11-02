package com.shana.model;

import java.util.Objects;

/**
 * 一条对话消息。
 *
 * @param role     角色（模型协议概念：谁在"扮演"什么）
 * @param senderId 发送者标识，群成员 ID；system / assistant 消息为空。
 *                 仅用于落库后的数据分析（按人聚合、画像、月度总结），
 *                 <b>不参与 prompt 组装</b>——传给模型的仍然只有 role 与 content。
 * @param content  消息内容
 */
public record ChatMessage(ChatRole role, String senderId, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    /** 不带发送者的消息（system / assistant，以及尚未接入成员体系的调用方）。 */
    public ChatMessage(ChatRole role, String content) {
        this(role, null, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    /** 某位群成员发出的消息，带发送者标识。 */
    public static ChatMessage user(String senderId, String content) {
        return new ChatMessage(ChatRole.USER, senderId, content);
    }

    /** 不知道发送者时使用（例如历史数据、离线导入）。 */
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

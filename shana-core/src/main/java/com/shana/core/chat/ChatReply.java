package com.shana.core.chat;

import com.shana.model.TokenUsage;

import java.util.List;
import java.util.Objects;

/**
 * 一次群聊问答的结果。
 *
 * <p>只暴露业务需要的信息，不把 {@code LlmClient}、厂商字段等实现细节漏给上层。
 *
 * @param conversationId 会话 ID
 * @param content        助手回复正文
 * @param usage          本次调用的 token 用量（含工具往返的每一轮），缺省为 {@link TokenUsage#unknown()}
 * @param toolsUsed      按执行顺序记录的工具名，空列表表示这次回答没查任何东西
 */
public record ChatReply(String conversationId, String content, TokenUsage usage, List<String> toolsUsed) {

    public ChatReply {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (usage == null) {
            usage = TokenUsage.unknown();
        }
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
    }

    public ChatReply(String conversationId, String content, TokenUsage usage) {
        this(conversationId, content, usage, List.of());
    }
}

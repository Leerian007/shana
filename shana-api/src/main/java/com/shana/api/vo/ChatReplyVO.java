package com.shana.api.vo;

import com.shana.core.chat.ChatReply;

/**
 * 问答接口的响应体。
 *
 * <p>不直接返回 {@link ChatReply}：领域模型可以自由演进，接口字段一旦发布就不能随便改。
 */
public record ChatReplyVO(String conversationId, String content, int promptTokens, int completionTokens) {

    public static ChatReplyVO from(ChatReply reply) {
        return new ChatReplyVO(
                reply.conversationId(),
                reply.content(),
                reply.usage().promptTokens(),
                reply.usage().completionTokens());
    }
}

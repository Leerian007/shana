package com.shana.api.vo;

import com.shana.core.chat.ChatReply;

import java.util.List;

/**
 * 问答接口的响应体。
 *
 * <p>不直接返回 {@link ChatReply}：领域模型可以自由演进，接口字段一旦发布就不能随便改。
 *
 * @param toolsUsed 这次回答实际调用过的工具名，空数组表示没查任何东西。
 *                  暴露它是为了可观测——"模型到底用没用工具"否则只能靠猜，
 *                  而工具调用的成本与延迟恰恰都花在这里。
 */
public record ChatReplyVO(String conversationId, String content, int promptTokens,
                          int completionTokens, List<String> toolsUsed) {

    public static ChatReplyVO from(ChatReply reply) {
        return new ChatReplyVO(
                reply.conversationId(),
                reply.content(),
                reply.usage().promptTokens(),
                reply.usage().completionTokens(),
                reply.toolsUsed());
    }
}

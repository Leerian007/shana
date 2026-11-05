package com.shana.agent;

import com.shana.agent.tool.ToolContext;
import com.shana.model.ChatMessage;
import com.shana.model.LlmProvider;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Agent 调用的输入。
 *
 * <p>{@code messages} 是调用方已经拼好的完整 prompt（人设、长期记忆、检索资料、对话历史），
 * Agent 只负责"在中间插入工具往返"，不参与 prompt 组装——那条链路由 core 掌管，
 * 混在一起会让"上下文从哪来"和"工具怎么调"互相牵扯。
 *
 * @param provider 目标模型提供方
 * @param messages 初始消息列表，至少一条
 * @param context  工具执行上下文（当前会话、发言人）
 */
public record AgentRequest(LlmProvider provider, List<ChatMessage> messages, ToolContext context) {

    public AgentRequest {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
    }
}

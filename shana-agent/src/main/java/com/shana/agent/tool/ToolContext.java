package com.shana.agent.tool;

/**
 * 工具执行时的请求上下文。
 *
 * <p>为什么要单独传，而不是让工具从 {@code arguments} 里读会话 ID：<b>会话 ID 是权限边界，
 * 不是参数</b>。如果模型能在入参里填 {@code conversationId}，它就能（因为幻觉或被诱导）
 * 去查另一个群的聊天记录——一个只该读本群的工具，变成了跨群数据泄露的入口。
 * 放在上下文里，工具只能读到调用方给的那一个值，模型连改的机会都没有。
 *
 * <p>同理，未来接入成员体系后，"当前发言人"也应该从这里拿，而不是让模型自己声明身份。
 *
 * @param conversationId 当前会话（群聊）ID，由调用方决定，不可为空
 * @param senderId       当前发言人标识，允许为空（未采集发送者时）
 */
public record ToolContext(String conversationId, String senderId) {

    public ToolContext {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
    }
}

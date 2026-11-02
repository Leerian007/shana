package com.shana.core.chat;

/**
 * 群聊问答的核心用例：把"一条用户消息"变成"一条助手回复"。
 *
 * <p>接口放在 core，实现依赖记忆与模型能力；上层（Controller、未来的定时任务）只依赖本接口，
 * 因此换模型、换记忆实现都不影响调用方。
 */
public interface ChatService {

    /**
     * 在指定会话中回复一条用户消息。
     *
     * @param conversationId 会话 ID（群聊 ID），用于隔离上下文
     * @param message        用户消息正文，调用方保证非空
     * @return 助手回复
     * @throws com.shana.common.exception.BusinessException 没有可用模型适配器或上游调用失败时抛出
     */
    ChatReply reply(String conversationId, String message);
}

package com.shana.core.chat;

/**
 * 群聊问答的核心用例：把"一条用户消息"变成"一条助手回复"。
 *
 * <p>接口放在 core，实现依赖记忆与模型能力；上层（Controller、未来的定时任务）只依赖本接口，
 * 因此换模型、换记忆实现都不影响调用方。
 */
public interface ChatService {

    /**
     * 不知道发送者时回复一条消息，等价于 {@code reply(conversationId, null, message)}。
     *
     * @param conversationId 会话 ID（群聊 ID），用于隔离上下文
     * @param message        用户消息正文，调用方保证非空
     * @return 助手回复
     */
    default ChatReply reply(String conversationId, String message) {
        return reply(conversationId, null, message);
    }

    /**
     * 在指定会话中回复一条用户消息。
     *
     * @param conversationId 会话 ID（群聊 ID），用于隔离上下文
     * @param senderId       发送者标识（群成员 ID），允许为空。
     *                       <b>只随消息落库，不进入 prompt</b>：生成回复的逻辑完全不依赖它，
     *                       它是为后续按人统计与画像保留的原始数据。
     * @param message        用户消息正文，调用方保证非空
     * @return 助手回复
     * @throws com.shana.common.exception.BusinessException 没有可用模型适配器或上游调用失败时抛出
     */
    ChatReply reply(String conversationId, String senderId, String message);
}

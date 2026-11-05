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

    /**
     * 流式回复：正文边生成边通过 {@code handler} 推出，全部完成后回调 {@link ChatStreamHandler#onComplete}。
     *
     * <p>与非流式版本共享同一套"写上下文 → 拼记忆 → 检索 → 交 Agent → 回写"的编排，
     * 因此两条路径对记忆与摘要的影响完全一致。区别只有一点：用户可以更早看到内容。
     *
     * <p>方法本身是<b>阻塞</b>的，调用方负责把它放到独立线程上（HTTP 层就是 SSE 的写线程）。
     * 这样"怎么并发"属于传输层的事，业务用例仍然是同步语义，不需要处理 Future 与超时组合。
     *
     * @param conversationId 会话 ID（群聊 ID）
     * @param senderId       发送者标识，允许为空，只随消息落库
     * @param message        用户消息正文
     * @param handler        过程回调；{@link ChatStreamHandler#NOOP} 表示只关心最终结果
     * @throws com.shana.common.exception.BusinessException 没有可用模型适配器或上游调用失败时抛出
     *         （流式请求此时已经发出了事件，由调用方翻译成错误事件而不是 HTTP 状态码）
     */
    void replyStream(String conversationId, String senderId, String message, ChatStreamHandler handler);
}

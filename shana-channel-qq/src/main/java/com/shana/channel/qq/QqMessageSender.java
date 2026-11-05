package com.shana.channel.qq;

/**
 * 把回复发回 QQ。
 *
 * <p>抽成接口是为了让编排逻辑（{@link QqChatHandler}）不依赖 OneBot 的动作名与 JSON 结构，
 * 测试时可以用一个记录器替身断言"发了什么"，而不必起一个假的 WebSocket 服务端。
 */
public interface QqMessageSender {

    /**
     * 回复一条消息。长文本会由实现自行分段，调用方不必关心条数。
     *
     * @param to      被回复的消息，提供群号/用户号与引用目标
     * @param content 回复正文，允许为空（实现应当跳过）
     */
    void reply(IncomingMessage to, String content);
}

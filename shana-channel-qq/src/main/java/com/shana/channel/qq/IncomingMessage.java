package com.shana.channel.qq;

import java.util.Objects;

/**
 * 从 OneBot 事件里抽出来的、业务真正需要的那几个字段。
 *
 * <p>不直接拿 JSON 往下传：解析和判断分开后，{@link ReplyPolicy} 可以在没有任何网络、
 * 没有 Spring 的情况下单测，"为什么这条没回"也能用一个小对象说清楚。
 *
 * <p>{@code groupId} 为 {@code null} 表示私聊，这是本类唯一的类型标记。
 * 单独加一个 {@code channel} 枚举会让构造变啰嗦，而这里只有两种形态。
 *
 * @param messageId       OneBot 的消息 ID，用于去重与引用回复
 * @param selfId          机器人自己的 QQ 号，来自事件里的 {@code self_id}
 * @param senderId        发送者 QQ 号
 * @param groupId         群号，私聊为 {@code null}
 * @param subType         私聊为 friend/group/other，群聊为 normal/anon，可空
 * @param text            纯文本正文，已去掉 @ 段与 CQ 码
 * @param mentionsSelf    是否 @ 了机器人；私聊恒为 false（私聊没有 @ 语义）
 * @param quotedMessageId 被引用消息的 ID，没有引用为 {@code null}
 */
public record IncomingMessage(
        String messageId,
        long selfId,
        long senderId,
        Long groupId,
        String subType,
        String text,
        boolean mentionsSelf,
        Long quotedMessageId) {

    public IncomingMessage {
        Objects.requireNonNull(text, "text must not be null");
    }

    public boolean isGroup() {
        return groupId != null;
    }

    /**
     * 会话 ID，直接当 {@code ChatService} 的 conversationId 用。
     *
     * <p>私聊与群聊分开命名空间，是为了让同一个人在两种场景下的上下文互不污染：
     * 群里聊的话题带到私聊里会很突兀，反过来还会泄露群里说过的话。
     */
    public String conversationId() {
        return isGroup() ? "qq:group:" + groupId : "qq:private:" + senderId;
    }

    /** 聚合了多条消息后的新对象，保留首条的消息 ID（引用回复时引用第一句更自然）。 */
    public IncomingMessage withText(String merged) {
        return new IncomingMessage(messageId, selfId, senderId, groupId, subType, merged,
                mentionsSelf, quotedMessageId);
    }
}

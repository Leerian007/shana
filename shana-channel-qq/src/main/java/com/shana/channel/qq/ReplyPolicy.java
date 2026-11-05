package com.shana.channel.qq;

import java.util.List;
import java.util.Set;

/**
 * 决定"这条消息要不要回"。
 *
 * <p>规则集中在一个纯函数里，是因为接入 IM 之后最容易出的问题不是模型答得不好，
 * 而是<b>根本不该回的消息回了</b>：自己发的话被当成用户输入、群临时会话当成好友、
 * 陌生人的试探性提问全都响应，轻则刷屏，重则被举报。
 *
 * <p>拒绝原因做成枚举而不是 boolean，是为了让日志能直接回答"为什么没回"，
 * 否则排查时只能靠猜。
 */
public final class ReplyPolicy {

    /** 拒绝原因。命中任何一条都不回，{@link #OK} 才回。 */
    public enum Rejection {
        /** 空正文（纯图片、纯表情），没有可问的内容 */
        EMPTY_TEXT,
        /** 自己发出去的消息，忽略，否则会自问自答 */
        SELF,
        /** 不在用户白名单里 */
        USER_NOT_ALLOWED,
        /** 私聊总开关关着 */
        PRIVATE_DISABLED,
        /** 私聊子类型不在允许范围（默认只放行 friend） */
        PRIVATE_SUB_TYPE,
        /** 群聊总开关关着 */
        GROUP_DISABLED,
        /** 群不在白名单里 */
        GROUP_NOT_ALLOWED,
        /** 群聊里没有 @ 机器人 */
        NOT_MENTIONED
    }

    /**
     * 判断结果。
     *
     * @param allowed   是否回复
     * @param rejection 拒绝原因，允许时为 {@code null}
     */
    public record Decision(boolean allowed, Rejection rejection) {

        static Decision allow() {
            return new Decision(true, null);
        }

        static Decision deny(Rejection rejection) {
            return new Decision(false, rejection);
        }
    }

    private final boolean groupEnabled;
    private final boolean requireAt;
    private final Set<String> groupWhitelist;
    private final boolean privateEnabled;
    private final Set<String> privateSubTypes;
    private final Set<String> userWhitelist;

    public ReplyPolicy(boolean groupEnabled,
                       boolean requireAt,
                       List<String> groupWhitelist,
                       boolean privateEnabled,
                       List<String> privateSubTypes,
                       List<String> userWhitelist) {
        this.groupEnabled = groupEnabled;
        this.requireAt = requireAt;
        this.groupWhitelist = Set.copyOf(groupWhitelist);
        this.privateEnabled = privateEnabled;
        this.privateSubTypes = Set.copyOf(privateSubTypes);
        this.userWhitelist = Set.copyOf(userWhitelist);
    }

    public Decision decide(IncomingMessage message) {
        if (message.text() == null || message.text().isBlank()) {
            return Decision.deny(Rejection.EMPTY_TEXT);
        }
        // 放在最前面：NapCat 开启 report_self_message 后，机器人自己的回复也会作为事件回来，
        // 不先挡住就会形成"自己回自己"的死循环
        if (message.senderId() == message.selfId()) {
            return Decision.deny(Rejection.SELF);
        }
        if (!userWhitelist.isEmpty() && !userWhitelist.contains(String.valueOf(message.senderId()))) {
            return Decision.deny(Rejection.USER_NOT_ALLOWED);
        }
        return message.isGroup() ? decideGroup(message) : decidePrivate(message);
    }

    private Decision decideGroup(IncomingMessage message) {
        if (!groupEnabled) {
            return Decision.deny(Rejection.GROUP_DISABLED);
        }
        if (!groupWhitelist.isEmpty() && !groupWhitelist.contains(String.valueOf(message.groupId()))) {
            return Decision.deny(Rejection.GROUP_NOT_ALLOWED);
        }
        if (requireAt && !message.mentionsSelf()) {
            return Decision.deny(Rejection.NOT_MENTIONED);
        }
        return Decision.allow();
    }

    private Decision decidePrivate(IncomingMessage message) {
        if (!privateEnabled) {
            return Decision.deny(Rejection.PRIVATE_DISABLED);
        }
        // 私聊没有 @ 可判断，准入只能靠"是不是好友"。
        // group（群临时会话）和 other（陌生人）默认都挡掉，否则任何人点开临时会话就能消耗模型调用
        String subType = message.subType() == null ? "" : message.subType();
        if (!privateSubTypes.contains(subType)) {
            return Decision.deny(Rejection.PRIVATE_SUB_TYPE);
        }
        return Decision.allow();
    }
}

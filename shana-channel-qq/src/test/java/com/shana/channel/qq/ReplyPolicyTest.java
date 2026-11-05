package com.shana.channel.qq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 准入策略测试。
 *
 * <p>这是接入 IM 后最该有测试的一层：答得好不好是模型的事，
 * "该不该答"错了却是事故——自己回自己会死循环，陌生人私聊全回会被举报。
 */
class ReplyPolicyTest {

    private static final long SELF = 10000L;
    private static final long BOT_FRIEND = 20001L;

    @Test
    @DisplayName("群里 @ 了机器人：放行")
    void shouldAllowGroupMention() {
        ReplyPolicy policy = groupPolicy(true, List.of());

        assertThat(policy.decide(groupMessage("在吗", true, "normal")).allowed()).isTrue();
    }

    @Test
    @DisplayName("群里没 @：不回复")
    void shouldRejectGroupWithoutMention() {
        ReplyPolicy policy = groupPolicy(true, List.of());

        ReplyPolicy.Decision decision = policy.decide(groupMessage("大家好", false, "normal"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.rejection()).isEqualTo(ReplyPolicy.Rejection.NOT_MENTIONED);
    }

    @Test
    @DisplayName("关闭 require-at 后，群里任意一句话都会触发")
    void shouldAllowGroupWithoutMentionWhenNotRequired() {
        ReplyPolicy policy = groupPolicy(false, List.of());

        assertThat(policy.decide(groupMessage("大家好", false, "normal")).allowed()).isTrue();
    }

    @Test
    @DisplayName("群不在白名单里：不回复")
    void shouldRejectGroupOutsideWhitelist() {
        ReplyPolicy policy = groupPolicy(true, List.of("99999"));

        ReplyPolicy.Decision decision = policy.decide(groupMessage("在吗", true, "normal"));

        assertThat(decision.rejection()).isEqualTo(ReplyPolicy.Rejection.GROUP_NOT_ALLOWED);
    }

    @Test
    @DisplayName("自己发的消息：不回复（否则自己回自己）")
    void shouldRejectSelfMessage() {
        ReplyPolicy policy = groupPolicy(true, List.of());
        IncomingMessage fromSelf = new IncomingMessage("1", SELF, SELF, 30001L,
                "normal", "我是机器人", true, null);

        assertThat(policy.decide(fromSelf).rejection()).isEqualTo(ReplyPolicy.Rejection.SELF);
    }

    @Test
    @DisplayName("空正文（纯图片）：不回复")
    void shouldRejectBlankText() {
        ReplyPolicy policy = groupPolicy(true, List.of());

        assertThat(policy.decide(groupMessage("   ", true, "normal")).rejection())
                .isEqualTo(ReplyPolicy.Rejection.EMPTY_TEXT);
    }

    @Test
    @DisplayName("好友私聊：不需要 @ 也能回复")
    void shouldAllowFriendPrivateMessage() {
        ReplyPolicy policy = privatePolicy(true, List.of("friend"));

        assertThat(policy.decide(privateMessage("在吗", "friend")).allowed()).isTrue();
    }

    @Test
    @DisplayName("群临时会话与陌生人私聊：默认不回复")
    void shouldRejectNonFriendPrivateSubTypes() {
        ReplyPolicy policy = privatePolicy(true, List.of("friend"));

        assertThat(policy.decide(privateMessage("在吗", "group")).rejection())
                .isEqualTo(ReplyPolicy.Rejection.PRIVATE_SUB_TYPE);
        assertThat(policy.decide(privateMessage("在吗", "other")).rejection())
                .isEqualTo(ReplyPolicy.Rejection.PRIVATE_SUB_TYPE);
    }

    @Test
    @DisplayName("私聊总开关关闭：任何私聊都不回")
    void shouldRejectPrivateWhenDisabled() {
        ReplyPolicy policy = privatePolicy(false, List.of("friend"));

        assertThat(policy.decide(privateMessage("在吗", "friend")).rejection())
                .isEqualTo(ReplyPolicy.Rejection.PRIVATE_DISABLED);
    }

    @Test
    @DisplayName("用户白名单非空时，名单外的人一律不回复（群聊同样生效）")
    void shouldRejectUserOutsideWhitelist() {
        ReplyPolicy policy = new ReplyPolicy(true, true, List.of(), true, List.of("friend"), List.of("88888"));

        assertThat(policy.decide(groupMessage("在吗", true, "normal")).rejection())
                .isEqualTo(ReplyPolicy.Rejection.USER_NOT_ALLOWED);
        assertThat(policy.decide(privateMessage("在吗", "friend")).rejection())
                .isEqualTo(ReplyPolicy.Rejection.USER_NOT_ALLOWED);
    }

    @Test
    @DisplayName("群功能关闭：即使 @ 了也不回")
    void shouldRejectGroupWhenDisabled() {
        ReplyPolicy policy = new ReplyPolicy(false, true, List.of(), true, List.of("friend"), List.of());

        assertThat(policy.decide(groupMessage("在吗", true, "normal")).rejection())
                .isEqualTo(ReplyPolicy.Rejection.GROUP_DISABLED);
    }

    private ReplyPolicy groupPolicy(boolean requireAt, List<String> whitelist) {
        return new ReplyPolicy(true, requireAt, whitelist, true, List.of("friend"), List.of());
    }

    private ReplyPolicy privatePolicy(boolean enabled, List<String> subTypes) {
        return new ReplyPolicy(true, true, List.of(), enabled, subTypes, List.of());
    }

    private IncomingMessage groupMessage(String text, boolean mentionsSelf, String subType) {
        return new IncomingMessage("1", SELF, BOT_FRIEND, 30001L, subType, text, mentionsSelf, null);
    }

    private IncomingMessage privateMessage(String text, String subType) {
        return new IncomingMessage("2", SELF, BOT_FRIEND, null, subType, text, false, null);
    }
}

package com.shana.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OneBot 事件解析测试。
 *
 * <p>直接用协议原文，不经过网络。这里覆盖的两类问题都很隐蔽：
 * array 与 CQ 码两种消息格式的差异，以及"@ 了谁"这种在多段消息里只看单个段就会判错的地方。
 */
class OneBotEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OneBotEventParser parser = new OneBotEventParser(objectMapper);

    @Test
    @DisplayName("群消息 @ 机器人：去掉 at 段，正文只留文本")
    void shouldParseGroupMention() {
        IncomingMessage message = parser.parse(json("""
                {"post_type":"message","message_type":"group","sub_type":"normal",
                 "self_id":10000,"user_id":20001,"group_id":30001,"message_id":9001,
                 "message":[{"type":"at","data":{"qq":"10000"}},
                            {"type":"text","data":{"text":" 今天谁发言最多"}}]}
                """)).orElseThrow();

        assertThat(message.text()).isEqualTo("今天谁发言最多");
        assertThat(message.mentionsSelf()).isTrue();
        assertThat(message.groupId()).isEqualTo(30001L);
        assertThat(message.senderId()).isEqualTo(20001L);
        assertThat(message.messageId()).isEqualTo("9001");
        assertThat(message.conversationId()).isEqualTo("qq:group:30001");
        assertThat(message.isGroup()).isTrue();
    }

    @Test
    @DisplayName("多条 at 段：只要有一个是自己就算叫我")
    void shouldRecognizeMentionAmongSeveral() {
        IncomingMessage message = parser.parse(json("""
                {"post_type":"message","message_type":"group","sub_type":"normal",
                 "self_id":10000,"user_id":20001,"group_id":30001,"message_id":9002,
                 "message":[{"type":"at","data":{"qq":"all"}},
                            {"type":"at","data":{"qq":10000}},
                            {"type":"text","data":{"text":"在吗"}}]}
                """)).orElseThrow();

        assertThat(message.mentionsSelf()).isTrue();
        assertThat(message.text()).isEqualTo("在吗");
    }

    @Test
    @DisplayName("私聊好友：没有 at 段，会话 ID 走 private 命名空间")
    void shouldParsePrivateFriend() {
        IncomingMessage message = parser.parse(json("""
                {"post_type":"message","message_type":"private","sub_type":"friend",
                 "self_id":10000,"user_id":20001,"message_id":9003,"message":"在吗"}
                """)).orElseThrow();

        assertThat(message.isGroup()).isFalse();
        assertThat(message.groupId()).isNull();
        assertThat(message.text()).isEqualTo("在吗");
        assertThat(message.mentionsSelf()).isFalse();
        assertThat(message.subType()).isEqualTo("friend");
        assertThat(message.conversationId()).isEqualTo("qq:private:20001");
    }

    @Test
    @DisplayName("CQ 码格式：解析出正文、@ 目标与引用 ID")
    void shouldParseCqCodeString() {
        IncomingMessage message = parser.parse(json("""
                {"post_type":"message","message_type":"group","sub_type":"normal",
                 "self_id":10000,"user_id":20001,"group_id":30001,"message_id":9004,
                 "message":"[CQ:reply,id=8888][CQ:at,qq=10000] 帮我看下&#91;重点&#93;"}
                """)).orElseThrow();

        assertThat(message.text()).isEqualTo("帮我看下[重点]");
        assertThat(message.mentionsSelf()).isTrue();
        assertThat(message.quotedMessageId()).isEqualTo(8888L);
    }

    @Test
    @DisplayName("引用回复：reply 段的 id 被保留")
    void shouldCaptureQuotedMessageId() {
        IncomingMessage message = parser.parse(json("""
                {"post_type":"message","message_type":"group","sub_type":"normal",
                 "self_id":10000,"user_id":20001,"group_id":30001,"message_id":9005,
                 "message":[{"type":"reply","data":{"id":"7777"}},
                            {"type":"text","data":{"text":"继续"}}]}
                """)).orElseThrow();

        assertThat(message.quotedMessageId()).isEqualTo(7777L);
        assertThat(message.text()).isEqualTo("继续");
    }

    @Test
    @DisplayName("纯图片消息：正文为空，交给策略层判定为不处理")
    void shouldReturnEmptyTextForImageOnly() {
        IncomingMessage message = parser.parse(json("""
                {"post_type":"message","message_type":"group","sub_type":"normal",
                 "self_id":10000,"user_id":20001,"group_id":30001,"message_id":9006,
                 "message":[{"type":"image","data":{"file":"a.jpg"}}]}
                """)).orElseThrow();

        assertThat(message.text()).isEmpty();
    }

    @Test
    @DisplayName("非 message 事件（通知/请求）直接忽略")
    void shouldIgnoreNonMessageEvents() {
        assertThat(parser.parse(json("""
                {"post_type":"notice","notice_type":"group_increase","self_id":10000,"user_id":20001}
                """))).isEmpty();

        assertThat(parser.parse(json("""
                {"post_type":"request","request_type":"friend","self_id":10000,"user_id":20001}
                """))).isEmpty();
    }

    @Test
    @DisplayName("自己发出的消息回显（message_sent）不当作输入")
    void shouldIgnoreSelfEcho() {
        Optional<IncomingMessage> parsed = parser.parse(json("""
                {"post_type":"message_sent","message_type":"group","sub_type":"normal",
                 "self_id":10000,"user_id":10000,"group_id":30001,"message_id":9007,
                 "message":[{"type":"text","data":{"text":"我是机器人"}}]}
                """));

        assertThat(parsed).isEmpty();
    }

    @Test
    @DisplayName("群消息缺 group_id 时丢弃，避免把私聊上下文串进群里")
    void shouldDropGroupMessageWithoutGroupId() {
        assertThat(parser.parse(json("""
                {"post_type":"message","message_type":"group","self_id":10000,"user_id":20001,
                 "message_id":9008,"message":"在吗"}
                """))).isEmpty();
    }

    @Test
    @DisplayName("message_id 是数字时也能取到字符串形式")
    void shouldReadNumericMessageId() {
        IncomingMessage message = parser.parse(json("""
                {"post_type":"message","message_type":"private","sub_type":"friend",
                 "self_id":10000,"user_id":20001,"message_id":1234567890123,"message":"hi"}
                """)).orElseThrow();

        assertThat(message.messageId()).isEqualTo("1234567890123");
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

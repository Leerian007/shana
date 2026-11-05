package com.shana.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.core.chat.ChatReply;
import com.shana.core.chat.ChatService;
import com.shana.core.chat.ChatStreamHandler;
import com.shana.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编排层测试：事件进、回复出。
 *
 * <p>用直连线程池（{@code Runnable::run}）而不是真池，测试就是确定性的；
 * 唯一的不确定来源是防抖窗口，所以窗口压到 40ms、等待给到 2s。
 *
 * <p>这里替身只实现端口（{@link ChatService}、{@link QqMessageSender}），
 * 不碰 WebSocket，也不碰模型——真正想守住的是"什么消息会走到模型"这条边界。
 */
class QqChatHandlerTest {

    private static final long SELF = 10000L;
    private static final long SENDER = 20001L;
    private static final long GROUP = 30001L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingChatService chatService = new RecordingChatService();
    private final RecordingSender sender = new RecordingSender();
    private final Executor directExecutor = Runnable::run;

    @Test
    @DisplayName("群里 @ 机器人：走问答链路，回复发回原会话")
    void shouldAnswerGroupMention() throws Exception {
        QqChatHandler handler = handler();

        handler.onEvent(groupEvent("m1", "今天谁发言最多", true));

        assertThat(sender.await(2)).isTrue();
        assertThat(chatService.calls).hasSize(1);
        assertThat(chatService.calls.get(0)).containsExactly("qq:group:30001", String.valueOf(SENDER), "今天谁发言最多");
        assertThat(sender.messages).containsExactly("qq:group:30001|答：今天谁发言最多");
    }

    @Test
    @DisplayName("群里没 @：根本不进模型，也不发消息")
    void shouldIgnoreGroupMessageWithoutMention() throws Exception {
        QqChatHandler handler = handler();

        handler.onEvent(groupEvent("m1", "大家好", false));
        Thread.sleep(250);

        assertThat(chatService.calls).isEmpty();
        assertThat(sender.messages).isEmpty();
    }

    @Test
    @DisplayName("同一个人连发三条：合并成一次提问")
    void shouldMergeConsecutiveMessages() throws Exception {
        QqChatHandler handler = handler();

        handler.onEvent(groupEvent("m1", "那个", true));
        Thread.sleep(20);
        handler.onEvent(groupEvent("m2", "上个月的报表", true));
        Thread.sleep(20);
        handler.onEvent(groupEvent("m3", "能再发一下吗", true));

        assertThat(sender.await(2)).isTrue();
        Thread.sleep(200);
        assertThat(chatService.calls).hasSize(1);
        assertThat(chatService.calls.get(0)[2]).isEqualTo("那个\n上个月的报表\n能再发一下吗");
    }

    @Test
    @DisplayName("重复推送的消息 ID 只处理一次（断线重连后的常见现象）")
    void shouldIgnoreDuplicateMessageId() throws Exception {
        QqChatHandler handler = handler();

        handler.onEvent(groupEvent("dup", "在吗", true));
        assertThat(sender.await(2)).isTrue();
        handler.onEvent(groupEvent("dup", "在吗", true));
        Thread.sleep(250);

        assertThat(chatService.calls).hasSize(1);
    }

    @Test
    @DisplayName("模型调用失败时给出兜底回复，而不是静默")
    void shouldReplyWithFailureHint() throws Exception {
        chatService.failure = new IllegalStateException("上游超时");
        QqChatHandler handler = handler();

        handler.onEvent(groupEvent("m1", "在吗", true));

        assertThat(sender.await(2)).isTrue();
        assertThat(sender.messages).containsExactly("qq:group:30001|" + "抱歉，刚才没能答上来，稍后再问我一次。");
    }

    @Test
    @DisplayName("好友私聊：不需要 @ 也能触发")
    void shouldAnswerFriendPrivateMessage() throws Exception {
        QqChatHandler handler = handler();

        handler.onEvent(privateEvent("p1", "在吗", "friend"));

        assertThat(sender.await(2)).isTrue();
        assertThat(chatService.calls.get(0)[0]).isEqualTo("qq:private:20001");
    }

    @Test
    @DisplayName("群临时会话：默认不回复")
    void shouldIgnoreGroupTemporaryChat() throws Exception {
        QqChatHandler handler = handler();

        handler.onEvent(privateEvent("p1", "在吗", "group"));
        Thread.sleep(250);

        assertThat(chatService.calls).isEmpty();
    }

    private QqChatHandler handler() {
        OneBotEventParser parser = new OneBotEventParser(objectMapper);
        ReplyPolicy policy = new ReplyPolicy(true, true, List.of(), true, List.of("friend"), List.of());
        return new QqChatHandler(parser, policy, chatService, sender, directExecutor,
                Duration.ofMillis(40), 2000);
    }

    private JsonNode groupEvent(String messageId, String text, boolean at) throws Exception {
        String segments = at
                ? "[{\"type\":\"at\",\"data\":{\"qq\":\"" + SELF + "\"}},{\"type\":\"text\",\"data\":{\"text\":\"" + text + "\"}}]"
                : "[{\"type\":\"text\",\"data\":{\"text\":\"" + text + "\"}}]";
        String json = """
                {"post_type":"message","message_type":"group","sub_type":"normal",
                 "self_id":%d,"user_id":%d,"group_id":%d,"message_id":"%s","message":%s}
                """.formatted(SELF, SENDER, GROUP, messageId, segments);
        return objectMapper.readTree(json);
    }

    private JsonNode privateEvent(String messageId, String text, String subType) throws Exception {
        String json = """
                {"post_type":"message","message_type":"private","sub_type":"%s",
                 "self_id":%d,"user_id":%d,"message_id":"%s","message":"%s"}
                """.formatted(subType, SELF, SENDER, messageId, text);
        return objectMapper.readTree(json);
    }

    private static final class RecordingChatService implements ChatService {

        private final List<String[]> calls = new CopyOnWriteArrayList<>();
        private RuntimeException failure;

        @Override
        public ChatReply reply(String conversationId, String senderId, String message) {
            if (failure != null) {
                throw failure;
            }
            calls.add(new String[]{conversationId, senderId, message});
            return new ChatReply(conversationId, "答：" + message, TokenUsage.unknown());
        }

        @Override
        public void replyStream(String conversationId, String senderId, String message, ChatStreamHandler handler) {
            throw new UnsupportedOperationException("QQ 渠道不走流式");
        }
    }

    private static final class RecordingSender implements QqMessageSender {

        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void reply(IncomingMessage to, String content) {
            messages.add(to.conversationId() + "|" + content);
            latch.countDown();
        }

        private boolean await(long seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }
    }
}

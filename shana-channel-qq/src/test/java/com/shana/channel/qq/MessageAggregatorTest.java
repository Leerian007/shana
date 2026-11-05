package com.shana.channel.qq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息聚合测试。
 *
 * <p>窗口定时器是真时间，所以等待时间给得比窗口宽一个数量级（窗口 150ms、最多等 2s），
 * 避免在 CI 上偶尔抖动。断言的是"合并了几次、内容对不对"，不依赖具体时刻。
 */
class MessageAggregatorTest {

    @Test
    @DisplayName("窗口内的连续消息合并成一条，只触发一次")
    void shouldMergeMessagesWithinWindow() throws Exception {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (MessageAggregator aggregator = new MessageAggregator(
                Duration.ofMillis(150), 2000, message -> {
            received.add(message);
            latch.countDown();
        })) {
            aggregator.accept(message("m1", "那个"));
            Thread.sleep(50);
            aggregator.accept(message("m2", "刚才的报表"));
            Thread.sleep(50);
            aggregator.accept(message("m3", "能再发一下吗"));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            // 窗口会滑动，第三条之后还会再等一个窗口；这里再等一轮确认没有第二次触发
            Thread.sleep(300);
            assertThat(received).hasSize(1);
            assertThat(received.get(0).text()).isEqualTo("那个\n刚才的报表\n能再发一下吗");
            // 合并后保留首条 ID，引用回复时引用第一句
            assertThat(received.get(0).messageId()).isEqualTo("m1");
        }
    }

    @Test
    @DisplayName("不同会话各自聚合，互不干扰")
    void shouldKeepConversationsSeparate() throws Exception {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        try (MessageAggregator aggregator = new MessageAggregator(
                Duration.ofMillis(120), 2000, message -> {
            received.add(message);
            latch.countDown();
        })) {
            aggregator.accept(new IncomingMessage("a1", 1L, 2L, 100L, "normal", "群里的问题", true, null));
            aggregator.accept(new IncomingMessage("b1", 1L, 3L, null, "friend", "私聊的问题", false, null));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).extracting(IncomingMessage::conversationId)
                    .containsExactlyInAnyOrder("qq:group:100", "qq:private:3");
        }
    }

    @Test
    @DisplayName("同一个群里两个人同时说话：各自成桶，不会拼成一句")
    void shouldNotMergeDifferentSenders() throws Exception {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        try (MessageAggregator aggregator = new MessageAggregator(
                Duration.ofMillis(150), 2000, message -> {
            received.add(message);
            latch.countDown();
        })) {
            aggregator.accept(new IncomingMessage("a1", 10000L, 20001L, 30001L, "normal", "第一人的问题", true, null));
            aggregator.accept(new IncomingMessage("b1", 10000L, 20002L, 30001L, "normal", "第二人的问题", true, null));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(2);
            assertThat(received).extracting(IncomingMessage::text)
                    .containsExactlyInAnyOrder("第一人的问题", "第二人的问题");
        }
    }

    @Test
    @DisplayName("聚合正文超过上限时截断，不会把整篇文档塞给模型")
    void shouldCapMergedLength() throws Exception {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (MessageAggregator aggregator = new MessageAggregator(
                Duration.ofMillis(120), 10, message -> {
            received.add(message);
            latch.countDown();
        })) {
            aggregator.accept(message("m1", "一二三四五"));
            aggregator.accept(message("m2", "六七八九十"));
            aggregator.accept(message("m3", "这段应该被丢掉"));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get(0).text()).hasSizeLessThanOrEqualTo(11);
            assertThat(received.get(0).text()).doesNotContain("这段应该被丢掉");
        }
    }

    @Test
    @DisplayName("关闭后不再分发，避免停机时还在调模型")
    void shouldStopDispatchingAfterClose() throws Exception {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        MessageAggregator aggregator = new MessageAggregator(Duration.ofMillis(80), 2000, received::add);
        aggregator.close();

        aggregator.accept(message("m1", "还在吗"));
        Thread.sleep(250);

        assertThat(received).isEmpty();
    }

    private IncomingMessage message(String id, String text) {
        return new IncomingMessage(id, 10000L, 20001L, 30001L, "normal", text, true, null);
    }
}

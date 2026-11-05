package com.shana.channel.qq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 把同一个人在同一个会话里连续发的几条消息拼成一条。
 *
 * <p>IM 里的打字习惯和输入框不一样：一句话往往被拆成"那个" / "刚才的报表" / "能再发一下吗"三条。
 * 每条都单独起一次模型调用，既贵又答非所问——模型看到"那个"完全不知道指什么。
 * 等待一个短窗口再合并，是这类接入里性价比最高的一处优化。
 *
 * <p>窗口是<b>滑动</b>的：每来一条新消息就重置计时器。所以连续输入的用户只会被处理一次，
 * 而说完一句就停下的用户最多多等一个窗口（默认 900ms），主观上察觉不到。
 *
 * <p>合并只保留首条的消息 ID：引用回复时引用第一句，比引用最后半句更符合对话习惯。
 */
public final class MessageAggregator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MessageAggregator.class);

    private final Duration window;
    private final int maxChars;
    private final Consumer<IncomingMessage> sink;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MessageAggregator(Duration window, int maxChars, Consumer<IncomingMessage> sink) {
        this(window, maxChars, sink, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shana-qq-debounce");
            thread.setDaemon(true);
            return thread;
        }));
    }

    MessageAggregator(Duration window, int maxChars, Consumer<IncomingMessage> sink,
                      ScheduledExecutorService scheduler) {
        this.window = window;
        this.maxChars = maxChars;
        this.sink = sink;
        this.scheduler = scheduler;
    }

    public void accept(IncomingMessage message) {
        if (closed) {
            return;
        }
        // 桶按「会话 + 发送者」划分：群里两个人同时 @ 机器人时，各自的话不能拼成一句，
        // 否则会被当成一个人在连着说，回答也会张冠李戴
        String key = keyOf(message);
        pendings.compute(key, (bucketKey, existing) -> {
            // 已经 flush 的桶不能复用：那批消息已经在处理了，新消息必须另起一轮
            Pending pending = existing == null || existing.fired ? new Pending(message) : existing;
            pending.merge(message, maxChars);
            pending.reschedule(bucketKey);
            return pending;
        });
    }

    private static String keyOf(IncomingMessage message) {
        return message.conversationId() + "#" + message.senderId();
    }

    private void fire(String conversationId, Pending pending) {
        if (!pendings.remove(conversationId, pending)) {
            // 被更新的消息替换掉了，交给后来者处理
            return;
        }
        pending.markFired();
        IncomingMessage merged = pending.merged();
        if (merged.text().isBlank()) {
            return;
        }
        try {
            sink.accept(merged);
        } catch (RuntimeException e) {
            // 下游抛错不能弄死这个调度线程，否则整个渠道静默失效
            log.warn("聚合后分发失败 conversation={}", conversationId, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        pendings.values().forEach(Pending::cancel);
        pendings.clear();
        scheduler.shutdownNow();
    }

    private final class Pending {

        private final IncomingMessage first;
        private final List<String> parts = new ArrayList<>();
        private ScheduledFuture<?> task;
        /** 已分发标记。volatile 是为了让 accept 侧的快速判断能看到调度线程的写入。 */
        private volatile boolean fired;
        private int length;

        private Pending(IncomingMessage first) {
            this.first = first;
        }

        private synchronized void markFired() {
            fired = true;
        }

        private synchronized void merge(IncomingMessage message, int limit) {
            if (fired) {
                return;
            }
            if (length >= limit) {
                return;
            }
            String text = message.text();
            int room = limit - length;
            if (text.length() > room) {
                text = text.substring(0, room);
            }
            parts.add(text);
            length += text.length() + 1;
        }

        private synchronized void reschedule(String conversationId) {
            if (task != null) {
                task.cancel(false);
            }
            task = scheduler.schedule(() -> fire(conversationId, this),
                    window.toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized IncomingMessage merged() {
            return first.withText(String.join("\n", parts).strip());
        }

        private synchronized void cancel() {
            if (task != null) {
                task.cancel(false);
            }
        }
    }
}

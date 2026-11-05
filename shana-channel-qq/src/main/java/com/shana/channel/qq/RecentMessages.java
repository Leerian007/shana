package com.shana.channel.qq;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最近处理过的消息 ID，用于去重。
 *
 * <p>为什么需要：WebSocket 断线重连后，NapCat 可能把未确认的消息再推一遍；
 * 而重连本身在弱网下可能一分钟发生好几次。没有去重的话，一句"在吗"会被回答三遍。
 *
 * <p>用固定容量的 LRU 而不是无界 Set：进程长期运行，无界集合迟早变成内存泄漏。
 * 容量取 512 是因为真正会同时重发的只有最近那一小段，留两个数量级的余量足够。
 *
 * <p>不是线程安全的实现，由 {@link QqChatHandler} 在单线程回调里使用；
 * 加锁的成本在这个量级上没有意义。
 */
final class RecentMessages {

    private static final int DEFAULT_CAPACITY = 512;

    private final Map<String, Boolean> seen;

    RecentMessages() {
        this(DEFAULT_CAPACITY);
    }

    RecentMessages(int capacity) {
        this.seen = new LinkedHashMap<>(capacity, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * @return true 表示这条消息第一次出现；false 表示是重发的
     */
    boolean firstTime(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            // 拿不到 ID 时宁可放过也不要错杀：漏一次去重的代价远小于所有消息都被当成重复
            return true;
        }
        return seen.put(messageId, Boolean.TRUE) == null;
    }
}

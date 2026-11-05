package com.shana.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.core.chat.ChatService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * QQ 渠道装配。
 *
 * <p>整块能力由一个开关控制：{@code shana.qq.enabled}。默认关闭，
 * 因为绝大多数部署根本没有 NapCat——关着的时候一个线程都不会起，
 * 也不会在日志里刷"连接失败"，比"开着但连不上"友好得多。
 *
 * <p>线程池单独一个，不复用 {@code memoryExecutor}：QQ 问答一次要等模型几秒，
 * 和长期记忆摘要抢线程会让摘要持续滞后；反过来摘要堆积也不该拖慢回复。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shana.qq", name = "enabled", havingValue = "true")
public class QqChannelConfiguration {

    @Bean
    OneBotEventParser oneBotEventParser(ObjectMapper objectMapper) {
        return new OneBotEventParser(objectMapper);
    }

    @Bean
    ReplyPolicy replyPolicy(QqProperties properties) {
        return new ReplyPolicy(
                properties.groupEnabledOrDefault(),
                properties.requireAtOrDefault(),
                properties.groupWhitelistOrEmpty(),
                properties.privateEnabledOrDefault(),
                properties.privateSubTypesOrDefault(),
                properties.userWhitelistOrEmpty());
    }

    @Bean
    QqMessageSender qqMessageSender(OneBotWsClient client, ObjectMapper objectMapper, QqProperties properties) {
        ReplyChunker chunker = new ReplyChunker(properties.replyMaxCharsOrDefault());
        return new OneBotMessageSender(client, objectMapper, chunker, properties.replyIntervalOrDefault());
    }

    @Bean(name = "qqExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor qqExecutor(QqProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.executorCoreSizeOrDefault());
        executor.setMaxPoolSize(properties.executorMaxSizeOrDefault());
        executor.setQueueCapacity(properties.executorQueueCapacityOrDefault());
        executor.setThreadNamePrefix("shana-qq-");
        // 队列满就拒绝：群消息可以丢（用户会再问一次），无限堆积只会让所有人一起等
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * 客户端在 handler 之前创建：handler 需要它来发消息，而它的事件监听由
     * {@link QqBotLifecycle} 在构造时接上，避免两个 Bean 循环依赖。
     */
    @Bean(destroyMethod = "close")
    OneBotWsClient oneBotWsClient(QqProperties properties, ObjectMapper objectMapper) {
        return new OneBotWsClient(
                properties.wsUrlOrDefault(),
                properties.accessToken(),
                properties.apiTimeoutOrDefault(),
                properties.reconnectInitialDelayOrDefault(),
                properties.reconnectMaxDelayOrDefault(),
                properties.heartbeatIntervalOrDefault(),
                objectMapper);
    }

    @Bean(destroyMethod = "close")
    QqChatHandler qqChatHandler(OneBotEventParser parser,
                                ReplyPolicy policy,
                                ChatService chatService,
                                QqMessageSender sender,
                                @Qualifier("qqExecutor") Executor qqExecutor,
                                QqProperties properties) {
        return new QqChatHandler(parser, policy, chatService, sender, qqExecutor,
                properties.debounceWindowOrDefault(), properties.debounceMaxCharsOrDefault());
    }

    @Bean
    QqBotLifecycle qqBotLifecycle(OneBotWsClient client, QqChatHandler handler) {
        return new QqBotLifecycle(client, handler);
    }
}

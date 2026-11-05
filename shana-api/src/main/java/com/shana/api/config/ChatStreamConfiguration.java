package com.shana.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 流式问答专用线程池。
 *
 * <p><b>为什么不复用 Tomcat 的请求线程？</b>SSE 的写法是"Controller 立刻返回一个 emitter，
 * 由别的线程继续往里写"。如果就地同步生成，请求线程会被占满整个生成过程（可能几十秒），
 * 并发几十个人就能把 Web 容器拖垮。这跟普通接口"快进快出"的模型完全不同。
 *
 * <p><b>为什么不复用 {@code @Async} 的默认池？</b>那个池跑的是长期记忆摘要这类后台任务，
 * 一旦被慢请求灌满，摘要就会被无限期推迟，而摘要推迟又会拖累下一轮对话的上下文质量。
 * 两类任务的生命周期差别太大，混在一个池里就是互相拖累。
 *
 * <p><b>队列为什么故意留小？</b>对流式请求来说，排队等于"客户端已经连上、却一个字节都收不到"，
 * 从用户角度看和卡死没有区别。与其排队几十秒再失败，不如立刻回 503 让它重试。
 */
@Configuration
public class ChatStreamConfiguration {

    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor chatStreamExecutor(
            @Value("${shana.stream.executor.core-size:4}") int coreSize,
            @Value("${shana.stream.executor.max-size:16}") int maxSize,
            @Value("${shana.stream.executor.queue-capacity:64}") int queueCapacity) {
        if (coreSize < 1 || maxSize < coreSize) {
            throw new IllegalArgumentException("shana.stream.executor 的 core-size/max-size 配置不合法");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("shana-stream-");
        // 队列满了直接拒绝，由 Controller 翻译成"服务暂不可用"，而不是无限堆积
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关闭时不等任务跑完：那些连接的客户端早就该收到结束了，继续跑只会拖慢停机
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}

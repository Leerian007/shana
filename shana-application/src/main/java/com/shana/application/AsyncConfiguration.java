package com.shana.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 后台任务线程池。
 *
 * <p>看起来"多余"，其实是有原因的：Spring Boot 的默认异步池 {@code applicationTaskExecutor}
 * 带 {@code @ConditionalOnMissingBean(Executor.class)}，<b>只要容器里出现任何一个 Executor Bean
 * 它就不再创建</b>。加入流式问答的线程池之后，{@code @Async} 会默默改用那个池——
 * 摘要任务于是和 SSE 抢线程，异常时还不会被发现（表现为"偶尔不更新长期记忆"）。
 *
 * <p>与其依赖这个隐式规则，不如把默认池显式声明出来：名字、容量、拒绝策略都写在这儿，
 * {@code @Async} 通过 {@link AsyncConfigurer} 明确指向它。流式池再怎么调，也影响不到摘要。
 *
 * <p>容量取小值：摘要任务是"每次对话触发一次、每次几秒"的轻活，
 * 真正需要的是<b>不丢任务</b>而不是高并发，所以队列给足。
 */
@Configuration
public class AsyncConfiguration {

    @Bean(name = "memoryExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 队列给大：摘要可以慢，但不能丢——丢了游标不推进，下次对话还会重来，白白多烧一次模型调用
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("shana-async-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean
    public AsyncConfigurer asyncConfigurer(@Qualifier("memoryExecutor") Executor memoryExecutor) {
        return new AsyncConfigurer() {
            @Override
            public Executor getAsyncExecutor() {
                return memoryExecutor;
            }
        };
    }
}

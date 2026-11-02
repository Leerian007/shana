package com.shana.model.adapter;

import com.shana.model.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 条件装配 OpenAI 兼容适配器。
 *
 * <p>没配 api-key 时容器里就没有 {@link LlmClient} 实现，{@code LlmClientRegistry} 自然为空，
 * 调用方拿到的是"服务暂不可用"而不是启动失败——本地没 key 也能把整个工程跑起来。
 *
 * <p>为什么用 {@link ConditionalOnExpression} 而不是 {@code @ConditionalOnProperty}：
 * 配置文件里写了 {@code api-key:}（YAML 空值）时，{@code @ConditionalOnProperty} 会认为"已配置"，
 * 于是拿空 key 去调上游，每次都是 401，而且看起来像上游故障。这里直接判断字符串长度，
 * 空值一律视为"未配置"。
 *
 * <p>{@code RestClient.Builder} 由 Spring Boot 自动配置提供（prototype 作用域），
 * 每个适配器拿到独立实例，互不影响。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${shana.llm.openai-compatible.api-key:}'.length() > 0")
class OpenAiCompatibleClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClientConfiguration.class);

    @Bean
    LlmClient openAiCompatibleLlmClient(OpenAiCompatibleProperties properties, RestClient.Builder restClientBuilder) {
        OpenAiCompatibleLlmClient client = OpenAiCompatibleLlmClient.create(properties, restClientBuilder);
        log.info("已启用 OpenAI 兼容模型适配器: baseUrl={}, model={}", properties.baseUrl(), properties.model());
        return client;
    }
}

package com.shana.model.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author: Ray
 * @CreateTime: 2026-11-02
 * @Description: OpenAi配置属性
 * @Version: 1.0
 *
 * <p>注意<b>不要给这个 record 增加第二个构造器</b>：Spring Boot 的构造器绑定要求
 * 记录类型只有一个构造器，多一个就会启动失败（报 "No default constructor found"）。
 * 新增配置项时直接加组件，并同步修改调用点。
 */

@ConfigurationProperties(prefix = "shana.llm.openai-compatible")
public record OpenAiCompatibleProperties(String baseUrl, String apiKey, String model, Double temperature,
                                         Boolean streamIncludeUsage) {

    /**
     * 流式请求是否让上游在收尾帧回报 token 用量。
     * <p>默认开：不开的话流式模式下永远统计不到成本，而"看起来省了钱、实际不知道花了多少"是最坏的情况。
     * 只有遇到不认识 {@code stream_options} 字段、直接返回 400 的服务才需要关掉。
     */
    public boolean includeStreamUsage() {
        return streamIncludeUsage == null || streamIncludeUsage;
    }
}

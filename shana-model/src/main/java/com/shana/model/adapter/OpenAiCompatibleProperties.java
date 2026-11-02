package com.shana.model.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author: Ray
 * @CreateTime: 2026-11-02
 * @Description: OpenAi配置属性
 * @Version: 1.0
 */

@ConfigurationProperties(prefix = "shana.llm.openai-compatible")
public record OpenAiCompatibleProperties(String baseUrl, String apiKey, String model, Double temperature) {

}

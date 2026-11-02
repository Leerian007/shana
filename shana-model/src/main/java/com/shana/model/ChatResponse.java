package com.shana.model;

import java.util.Objects;

/**
 * 一次模型调用的结果。
 *
 * @param content  模型回复正文
 * @param provider 实际使用的提供方
 * @param model    实际使用的模型名
 * @param usage    token 用量，适配器拿不到时返回 {@link TokenUsage#unknown()}
 */
public record ChatResponse(String content, LlmProvider provider, String model, TokenUsage usage) {

    public ChatResponse {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        if (usage == null) {
            usage = TokenUsage.unknown();
        }
    }
}

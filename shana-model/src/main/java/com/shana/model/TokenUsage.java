package com.shana.model;

/**
 * token 用量，用于成本统计与上下文体量控制。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    public static TokenUsage unknown() {
        return new TokenUsage(0, 0);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}

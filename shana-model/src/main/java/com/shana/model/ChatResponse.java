package com.shana.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用的结果。
 *
 * <p>两种合法形态，二者至少满足其一：
 * <ul>
 *   <li>有 {@code content}：正常回复；</li>
 *   <li>有 {@code toolCalls}：模型要求先调工具，此时 {@code content} 通常为 {@code null}。</li>
 * </ul>
 * 两者可以同时存在（模型一边说"我查一下"一边发起调用），所以不是二选一。
 *
 * @param content   模型回复正文，携带工具调用时可能为 null
 * @param provider  实际使用的提供方
 * @param model     实际使用的模型名
 * @param usage     token 用量，适配器拿不到时返回 {@link TokenUsage#unknown()}
 * @param toolCalls 模型要求执行的工具调用，没有时为空列表
 */
public record ChatResponse(String content, LlmProvider provider, String model,
                           TokenUsage usage, List<ToolCall> toolCalls) {

    public ChatResponse {
        Objects.requireNonNull(provider, "provider must not be null");
        if (usage == null) {
            usage = TokenUsage.unknown();
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (content == null && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("模型既没给内容也没要求调用工具");
        }
    }

    public ChatResponse(String content, LlmProvider provider, String model, TokenUsage usage) {
        this(content, provider, model, usage, List.of());
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}

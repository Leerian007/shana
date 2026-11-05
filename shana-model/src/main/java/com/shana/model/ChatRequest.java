package com.shana.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用请求。
 * <p>{@code model} 与采样参数允许为 {@code null}，由具体适配器回退到自身默认值。
 *
 * @param provider    目标模型提供方，决定由哪个 {@link LlmClient} 处理
 * @param model       模型名，例如 {@code gpt-4o-mini}、{@code qwen2.5:7b}
 * @param messages    对话消息列表，至少一条且不可变
 * @param temperature 采样温度，null 表示使用适配器默认值
 * @param maxTokens   最大输出 token 数，null 表示使用适配器默认值
 * @param tools       本次允许模型调用的工具，空列表表示<b>不传这个字段</b>（而不是传空数组）
 */
public record ChatRequest(
        LlmProvider provider,
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        List<ToolSpec> tools) {

    public ChatRequest {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 不带工具的请求。 */
    public ChatRequest(LlmProvider provider, String model, List<ChatMessage> messages,
                       Double temperature, Integer maxTokens) {
        this(provider, model, messages, temperature, maxTokens, List.of());
    }

    /** 只关心"用哪个模型、问什么"时的简化构造。 */
    public static ChatRequest of(LlmProvider provider, String model, List<ChatMessage> messages) {
        return new ChatRequest(provider, model, messages, null, null, List.of());
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}

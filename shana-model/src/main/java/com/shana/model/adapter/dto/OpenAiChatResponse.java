package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI 兼容协议的 /chat/completions 响应体。
 *
 * <p>只声明用得到的字段：上游每次升级都可能新增字段（如 {@code system_fingerprint}、{@code reasoning_content}），
 * {@code ignoreUnknown = true} 让这些字段不会打断反序列化。这里显式声明忽略，而不是依赖全局 Jackson 配置——
 * DTO 自己说清楚对协议的容忍度，将来改全局配置也不会把它弄崩。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatResponse(List<OpenAiChoice> choices, OpenAiUsage usage) {

    /**
     * choices 数组的元素。
     * <p>注意它不是 Message：上游把真正的消息包在 {@code message} 字段里。
     * {@code index}、{@code finish_reason} 本项目暂不使用，不声明。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiChoice(OpenAiChatMessage message) {
    }
}

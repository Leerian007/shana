package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * OpenAI 兼容协议的 /chat/completions 请求体。
 *
 * <p>{@code @JsonInclude(NON_NULL)} 让值为 null 的字段直接从 JSON 中消失：部分兼容服务对
 * {@code "temperature": null} 会直接返回 400，缺省不传才是安全的做法。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(String model, List<OpenAiChatMessage> messages, Double temperature,
                                Integer maxTokens) {
}

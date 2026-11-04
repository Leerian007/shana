package com.shana.model.adapter.dto;

import java.util.List;

/**
 * OpenAI 兼容协议的 /embeddings 请求体。
 *
 * <p>{@code input} 既可以是字符串数组也可以是单个字符串，这里只用数组：
 * 批量调用能把几十条 chunk 压进一次请求，比逐条调用快一个数量级。
 */
public record OpenAiEmbeddingRequest(String model, List<String> input) {
}

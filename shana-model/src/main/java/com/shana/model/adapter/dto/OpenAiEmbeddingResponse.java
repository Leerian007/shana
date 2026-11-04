package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * /embeddings 响应体。
 *
 * <p>{@code data} 是数组且带 {@code index}：批量请求时上游可能乱序返回，
 * 不能直接按下标对应入参，必须按 {@code index} 还原顺序。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiEmbeddingResponse(List<OpenAiEmbeddingData> data, OpenAiUsage usage) {

    /**
     * 单条向量。
     *
     * @param index     对应入参中的下标
     * @param embedding 稠密向量，JSON 是数字数组，Jackson 可直接反序列化为 float[]
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiEmbeddingData(Integer index, float[] embedding) {
    }
}

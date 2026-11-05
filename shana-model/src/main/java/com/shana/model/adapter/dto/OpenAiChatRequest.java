package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 兼容协议的 /chat/completions 请求体。
 *
 * <p>{@code @JsonInclude(NON_NULL)} 让值为 null 的字段直接从 JSON 中消失：部分兼容服务对
 * {@code "temperature": null} 会直接返回 400，缺省不传才是安全的做法。
 * {@code tools} 同理——没有工具时传 {@code "tools": []} 有的服务也会报错，所以干脆不传，
 * 这也正好对应"这一轮禁用工具"的语义（轮次超限后的兜底请求就靠它）。
 *
 * <p>{@code stream} 与 {@code streamOptions} 只用于流式请求，普通请求时为 null，
 * 序列化后整个字段消失——同一个记录同时承载两种模式，请求体格式只此一份。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(String model, List<OpenAiChatMessage> messages, Double temperature,
                                Integer maxTokens, List<OpenAiToolSpec> tools,
                                Boolean stream,
                                @JsonProperty("stream_options") OpenAiStreamOptions streamOptions) {

    /** 非流式请求：流式相关字段全部不传。 */
    public OpenAiChatRequest(String model, List<OpenAiChatMessage> messages, Double temperature,
                             Integer maxTokens, List<OpenAiToolSpec> tools) {
        this(model, messages, temperature, maxTokens, tools, null, null);
    }
}

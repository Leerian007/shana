package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 请求体里的工具声明（{@code tools[].function}）。
 *
 * <p>{@code parameters} 用 {@link JsonNode} 而不是 String：内部契约里存的是 JSON Schema 文本，
 * 如果这里也写 String，序列化会把它变成一个被转义的字符串（{@code "{\"type\":...}"}），
 * 上游会当成非法 schema 拒绝。转成 JsonNode 才是"把这段 JSON 嵌进去"的正确语义。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiToolSpec(String type, Function function) {

    public record Function(String name, String description, JsonNode parameters) {
    }

    public static OpenAiToolSpec ofFunction(String name, String description, JsonNode parameters) {
        return new OpenAiToolSpec("function", new Function(name, description, parameters));
    }
}

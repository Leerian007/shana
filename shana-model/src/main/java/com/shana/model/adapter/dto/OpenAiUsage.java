package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 响应里的 token 用量。
 *
 * <p>JSON 是下划线命名、Java 是驼峰，名字不一致，所以必须显式标注；
 * {@code total_tokens} 等暂时用不到的字段交由 {@code ignoreUnknown} 忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiUsage(@JsonProperty("prompt_tokens") int promptTokens,
                          @JsonProperty("completion_tokens") int completionTokens) {
}

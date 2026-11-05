package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * OpenAI 协议中的一条消息，请求与响应共用（上游把 assistant 的工具调用也放在 message 里）。
 *
 * <p>与 {@link OpenAiUsage} 一样用 {@link JsonNaming} 的蛇形策略映射
 * {@code toolCallId -> tool_call_id}、{@code toolCalls -> tool_calls}；
 * {@code role}、{@code content} 单词字段不受影响，正好省掉逐个 {@code @JsonProperty}。
 *
 * <p>{@code content} 在携带 {@code tool_calls} 的 assistant 消息里必须<b>省略</b>而不是传 null：
 * 部分兼容服务对 {@code "content": null} 的处理与 OpenAI 不一致，省略是唯一安全写法。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenAiChatMessage(String role, String content, String toolCallId,
                                List<OpenAiToolCall> toolCalls) {
}

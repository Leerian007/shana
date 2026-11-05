package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 流式请求的附加选项。
 *
 * <p>{@code include_usage} 是让上游在最后一个数据块里补一条 {@code usage} 字段。
 * 不开这个选项，流式响应里<b>拿不到 token 用量</b>——它不在任何增量里，
 * 只在收尾时单独发一次。要在流式模式下维持和非流式一致的成本可观测性，就必须显式开启。
 *
 * <p>极少数兼容服务不认识这个字段会直接返回 400，所以做成配置项（默认开），
 * 遇到不兼容的服务可以关掉，代价是 usage 变成 unknown。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiStreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
}

package com.shana.model.adapter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型返回的一次工具调用。
 *
 * <p>{@code arguments} 在协议里是<b>字符串</b>而不是对象：模型生成的是一段 JSON 文本，
 * 上游不会替我们解析。所以这里保持 String，由工具自己决定怎么读——顺便也让"模型给了坏 JSON"
 * 这个真实存在的问题显式暴露出来，而不是在反序列化阶段被悄悄吞掉。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiToolCall(String id, String type, Function function) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Function(String name, String arguments) {
    }
}

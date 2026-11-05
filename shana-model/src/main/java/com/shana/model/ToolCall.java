package com.shana.model;

import java.util.Objects;

/**
 * 模型发起的一次工具调用请求。
 *
 * <p>{@code id} 是上游生成的调用标识，<b>必须</b>原样回填到 {@link ChatMessage#toolCallId()} 对应的
 * tool 消息里：模型靠它把"我请求的那次调用"和"拿回来的结果"对上。丢弃或改写 id，多工具并发时
 * 结果就会串位，而模型不会报错，它会拿着 A 的结果解释 B。
 *
 * @param id        调用标识，由模型生成
 * @param name      工具名，对应 {@link ToolSpec#name()}
 * @param arguments JSON 格式入参，模型生成，可能是空字符串或非法 JSON
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (arguments == null || arguments.isBlank()) {
            // 无参工具时部分模型返回空字符串而不是 "{}"，统一成空对象，省得每个工具自己判空
            arguments = "{}";
        }
    }
}

package com.shana.model.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import com.shana.model.ToolCall;
import com.shana.model.ToolSpec;
import com.shana.model.adapter.dto.OpenAiChatMessage;
import com.shana.model.adapter.dto.OpenAiChatRequest;
import com.shana.model.adapter.dto.OpenAiChatResponse;
import com.shana.model.adapter.dto.OpenAiStreamOptions;
import com.shana.model.adapter.dto.OpenAiToolCall;
import com.shana.model.adapter.dto.OpenAiToolSpec;
import com.shana.model.adapter.dto.OpenAiUsage;

import java.util.List;
import java.util.Locale;

/**
 * 统一模型契约（{@code com.shana.model}）与 OpenAI 兼容协议 DTO 之间的双向转换。
 *
 * <p>放在适配器内部且不公开（包级可见）：协议 DTO 是"脏"的、跟着上游走的，
 * 不应该让 core 层或其它模块碰到它们，转换的边界就收在这一层。
 *
 * <p>本类不持有状态、不访问网络，所有方法都是纯函数，因此可以直接单测。
 */
final class OpenAiChatMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiChatMapper() {
    }

    /**
     * 把统一请求翻译成上游请求体。
     *
     * <p>{@code model} 与 {@code temperature} 优先取调用方传入的值，为空时回落到配置默认值——
     * 这样业务代码可以只关心"问什么"，不必关心"用哪个模型"。
     *
     * @param request  统一请求
     * @param defaults 适配器配置，提供模型名与温度的默认值
     */
    static OpenAiChatRequest toRequest(ChatRequest request, OpenAiCompatibleProperties defaults) {
        List<OpenAiChatMessage> messages = request.messages().stream()
                .map(OpenAiChatMapper::toMessage)
                .toList();
        return new OpenAiChatRequest(
                preferOr(request.model(), defaults.model()),
                messages,
                preferOr(request.temperature(), defaults.temperature()),
                request.maxTokens(),
                toTools(request.tools()));
    }

    /**
     * 在 {@link #toRequest} 的基础上加上流式开关。
     *
     * <p>复用同一个方法而不是复制一份：请求体的其余部分（消息、温度、工具）在两种模式下必须完全一致，
     * 否则会出现"同一个问题，非流式答对了、流式答错了"这种最难查的差异。
     */
    static OpenAiChatRequest toStreamRequest(ChatRequest request, OpenAiCompatibleProperties defaults) {
        OpenAiChatRequest base = toRequest(request, defaults);
        return new OpenAiChatRequest(base.model(), base.messages(), base.temperature(), base.maxTokens(),
                base.tools(), Boolean.TRUE,
                defaults.includeStreamUsage() ? new OpenAiStreamOptions(true) : null);
    }

    /**
     * 把上游响应翻译成统一响应。
     *
     * <p>上游返回空 choices 属于"服务不可用"；但 {@code content} 为空<b>不一定</b>是错误——
     * 模型要求调用工具时 content 就是 null。所以先看 tool_calls，再判断内容，
     * 否则每一次工具调用都会被误判成上游故障。
     *
     * @param response 上游响应体，允许为 null（例如 204 或空 body）
     * @param model    本次实际请求的模型名，用于回填 {@link ChatResponse#model()}
     */
    static ChatResponse toChatResponse(OpenAiChatResponse response, String model) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型服务未返回任何回复");
        }
        OpenAiChatResponse.OpenAiChoice choice = response.choices().get(0);
        OpenAiChatMessage message = choice == null ? null : choice.message();
        if (message == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型服务返回的回复缺少内容");
        }

        List<ToolCall> toolCalls = toToolCalls(message.toolCalls());
        if (message.content() == null && toolCalls.isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型服务返回的回复缺少内容");
        }
        return new ChatResponse(message.content(), LlmProvider.OPENAI_COMPATIBLE, model,
                toUsage(response.usage()), toolCalls);
    }

    /** 上游要小写角色名（system / user / assistant），枚举名是大写，必须显式转换。 */
    private static OpenAiChatMessage toMessage(ChatMessage message) {
        List<OpenAiToolCall> toolCalls = message.toolCalls().isEmpty()
                ? null
                : message.toolCalls().stream()
                .map(call -> new OpenAiToolCall(call.id(), "function",
                        new OpenAiToolCall.Function(call.name(), call.arguments())))
                .toList();
        return new OpenAiChatMessage(
                message.role().name().toLowerCase(Locale.ROOT),
                message.content(),
                message.toolCallId(),
                toolCalls);
    }

    /** 没有工具时返回 null，让字段整个消失；传空数组在部分兼容服务上会被判为非法。 */
    private static List<OpenAiToolSpec> toTools(List<ToolSpec> tools) {
        if (tools.isEmpty()) {
            return null;
        }
        return tools.stream()
                .map(spec -> OpenAiToolSpec.ofFunction(spec.name(), spec.description(), toJson(spec)))
                .toList();
    }

    private static JsonNode toJson(ToolSpec spec) {
        try {
            return JSON.readTree(spec.parametersSchema());
        } catch (Exception e) {
            // ToolSpec 构造时已经校验过，走到这里说明有人绕过校验或改了实现，属于编程错误
            throw new IllegalStateException("工具参数 schema 不是合法 JSON: " + spec.name(), e);
        }
    }

    private static List<ToolCall> toToolCalls(List<OpenAiToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls.stream()
                .filter(call -> call != null && call.function() != null && call.function().name() != null)
                .map(call -> new ToolCall(call.id(), call.function().name(), call.function().arguments()))
                .toList();
    }

    private static TokenUsage toUsage(OpenAiUsage usage) {
        return usage == null ? TokenUsage.unknown() : new TokenUsage(usage.promptTokens(), usage.completionTokens());
    }

    private static <T> T preferOr(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}

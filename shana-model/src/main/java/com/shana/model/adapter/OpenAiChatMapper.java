package com.shana.model.adapter;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import com.shana.model.adapter.dto.OpenAiChatMessage;
import com.shana.model.adapter.dto.OpenAiChatRequest;
import com.shana.model.adapter.dto.OpenAiChatResponse;
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
                request.maxTokens());
    }

    /**
     * 把上游响应翻译成统一响应。
     *
     * <p>上游返回空 choices 或空 content 都属于"服务不可用"，统一翻译成
     * {@link ErrorCode#SERVICE_UNAVAILABLE} 抛出，由上层决定如何展示。
     *
     * @param response 上游响应体，允许为 null（例如 204 或空 body）
     * @param model    本次实际请求的模型名，用于回填 {@link ChatResponse#model()}
     */
    static ChatResponse toChatResponse(OpenAiChatResponse response, String model) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型服务未返回任何回复");
        }
        OpenAiChatResponse.OpenAiChoice choice = response.choices().get(0);
        String content = (choice == null || choice.message() == null) ? null : choice.message().content();
        if (content == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型服务返回的回复缺少内容");
        }
        return new ChatResponse(content, LlmProvider.OPENAI_COMPATIBLE, model, toUsage(response.usage()));
    }

    /** 上游要小写角色名（system / user / assistant），枚举名是大写，必须显式转换。 */
    private static OpenAiChatMessage toMessage(ChatMessage message) {
        return new OpenAiChatMessage(message.role().name().toLowerCase(Locale.ROOT), message.content());
    }

    private static TokenUsage toUsage(OpenAiUsage usage) {
        return usage == null ? TokenUsage.unknown() : new TokenUsage(usage.promptTokens(), usage.completionTokens());
    }

    private static <T> T preferOr(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}

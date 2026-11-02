package com.shana.model.adapter;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmClient;
import com.shana.model.LlmProvider;
import com.shana.model.adapter.dto.OpenAiChatRequest;
import com.shana.model.adapter.dto.OpenAiChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 面向"OpenAI 兼容协议"的模型适配器：OpenAI、DeepSeek、通义、vLLM 等只要提供
 * {@code POST /chat/completions}，都能用本类接入，切换厂商只需要改 baseUrl 和模型名。
 *
 * <p>职责边界：本类只负责"发请求 + 把各种失败翻译成统一异常"，
 * 请求/响应与内部模型的字段转换全部交给 {@link OpenAiChatMapper}，因此协议细节可以单独测试。
 *
 * <p>线程安全：{@link RestClient} 是线程安全的，本类没有可变状态，可以并发调用。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final OpenAiCompatibleProperties properties;
    private final RestClient restClient;

    OpenAiCompatibleLlmClient(OpenAiCompatibleProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    /**
     * 按配置构建适配器：绑定 baseUrl 与鉴权头，并做启动期校验。
     *
     * <p>配置缺失时在**启动阶段**就抛异常（而不是等到第一次调用），
     * 这样"配错了"和"上游挂了"不会混成同一种现象。
     */
    public static OpenAiCompatibleLlmClient create(OpenAiCompatibleProperties properties,
                                                   RestClient.Builder builder) {
        String baseUrl = requireText(properties.baseUrl(), "shana.llm.openai-compatible.base-url");
        String apiKey = requireText(properties.apiKey(), "shana.llm.openai-compatible.api-key");
        RestClient restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        return new OpenAiCompatibleLlmClient(properties, restClient);
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        OpenAiChatRequest body = OpenAiChatMapper.toRequest(request, properties);
        log.debug("调用模型服务: model={}, messageCount={}", body.model(), body.messages().size());

        try {
            OpenAiChatResponse response = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(OpenAiChatResponse.class);
            return OpenAiChatMapper.toChatResponse(response, body.model());
        } catch (RestClientResponseException ex) {
            // 上游明确返回了 4xx/5xx：通常是 key 失效、模型名写错、余额不足
            log.warn("模型服务返回错误状态: status={}, model={}", ex.getStatusCode().value(), body.model());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "模型服务返回错误状态 " + ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            // 连不上、超时、DNS 失败
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "连接模型服务失败，请检查网络或 base-url 配置", ex);
        } catch (RestClientException ex) {
            // 其余情况：响应不是合法 JSON、字符集不支持等
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "调用模型服务失败: " + ex.getMessage(), ex);
        }
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必填配置: " + propertyName);
        }
        return value;
    }
}

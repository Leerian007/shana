package com.shana.model.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmProvider;
import com.shana.model.StreamingLlmClient;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 面向"OpenAI 兼容协议"的模型适配器：OpenAI、DeepSeek、通义、vLLM 等只要提供
 * {@code POST /chat/completions}，都能用本类接入，切换厂商只需要改 baseUrl 和模型名。
 *
 * <p>职责边界：本类只负责"发请求 + 把各种失败翻译成统一异常"，
 * 请求/响应与内部模型的字段转换全部交给 {@link OpenAiChatMapper}，
 * 流式帧的解析交给 {@link OpenAiStreamParser}，因此协议细节可以单独测试。
 *
 * <p><b>为什么普通调用用 {@link RestClient}，流式调用却直接用 JDK {@link HttpClient}？</b>
 * 因为流式的全部价值在于"边到边给"，而 {@code RestClient} 的响应体是否边收边读取决于底层
 * 请求工厂的实现——某些实现会先把整个响应读进内存再交出来，那样 SSE 会退化成"最后一次性返回"，
 * 而且从外部完全看不出来。用 {@code BodyHandlers.ofInputStream()} 是明确拿到一条流，
 * 不依赖框架版本与默认工厂的行为。代价是多写几十行，换来的是可预期的延迟。
 *
 * <p>线程安全：{@link RestClient} 与 {@link HttpClient} 都是线程安全的，本类没有可变状态，可以并发调用。
 */
public class OpenAiCompatibleLlmClient implements StreamingLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /** 建连超时。它只管"连不上"，不管"很久没有新数据"——流式响应的空闲检测在上层。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 非 2xx 时最多带回多少字节的错误信息，够看清原因又不至于把日志撑爆。 */
    private static final int MAX_ERROR_BODY_BYTES = 2048;

    private final OpenAiCompatibleProperties properties;
    private final RestClient restClient;
    private final HttpClient httpClient;
    private final String apiKey;
    /** 流式端点完整 URL；base-url 缺失时为 null，此时流式调用直接报"未配置"。 */
    private final String streamUrl;

    OpenAiCompatibleLlmClient(OpenAiCompatibleProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        this.apiKey = properties.apiKey();
        this.streamUrl = resolveStreamUrl(properties.baseUrl());
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
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

    /**
     * 流式对话补全：正文增量边到边交给 {@code onText}，函数返回时仍然是完整的 {@link ChatResponse}。
     *
     * <p>三条失败路径分别翻译：非 2xx 带回上游错误正文（否则只剩一个状态码，根本看不出是
     * key 失效还是模型名写错）、建连失败、读到一半断开。后两种都会让用户看到"答到一半停了"，
     * 所以错误信息要能区分开。
     *
     * <p>{@code onText} 抛出的异常<b>原样向上冒泡</b>（不做包装）：它的唯一来源是"客户端已经断开"，
     * 上层需要凭这个类型立刻停止生成，而不是继续对着一个没人听的连接烧 token。
     */
    @Override
    public ChatResponse streamChat(ChatRequest request, Consumer<String> onText) {
        if (streamUrl == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "未配置 shana.llm.openai-compatible.base-url，无法发起流式请求");
        }

        OpenAiChatRequest body = OpenAiChatMapper.toStreamRequest(request, properties);
        log.debug("发起流式调用: model={}, messageCount={}", body.model(), body.messages().size());

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(streamUrl))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + (apiKey == null ? "" : apiKey))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();

        long startedAt = System.nanoTime();
        HttpResponse<InputStream> response = send(httpRequest);
        if (response.statusCode() / 100 != 2) {
            String detail = readErrorBody(response.body());
            log.warn("模型服务流式接口返回错误状态: status={}, model={}", response.statusCode(), body.model());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "模型服务返回错误状态 " + response.statusCode()
                            + (detail.isBlank() ? "" : "：" + detail));
        }

        boolean[] firstChunkSeen = {false};
        Consumer<String> sink = text -> {
            if (!firstChunkSeen[0]) {
                firstChunkSeen[0] = true;
                log.debug("流式首段到达: model={}, firstTokenMs={}", body.model(), elapsedMs(startedAt));
            }
            onText.accept(text);
        };

        OpenAiStreamParser parser = new OpenAiStreamParser(sink);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.accept(line);
                if (parser.isDone()) {
                    break;
                }
            }
        } catch (IOException ex) {
            // 已经吐出去的内容收不回来了，只能如实报"中途断开"
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "读取模型流式响应失败，连接可能已中断", ex);
        }

        ChatResponse result = parser.toResponse(body.model(), provider());
        if ("length".equals(parser.finishReason())) {
            log.warn("流式回复被 max_tokens 截断: model={}", body.model());
        }
        log.debug("流式调用结束: model={}, totalMs={}, hasToolCalls={}",
                body.model(), elapsedMs(startedAt), result.hasToolCalls());
        return result;
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "流式调用被中断", ex);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "连接模型服务失败，请检查网络或 base-url 配置", ex);
        }
    }

    private static String readErrorBody(InputStream body) {
        try (InputStream in = body) {
            return new String(in.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            return "";
        }
    }

    private static String toJson(OpenAiChatRequest body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("流式请求体序列化失败", ex);
        }
    }

    /** base-url 允许带结尾斜杠，这里统一剥掉，避免拼出 //chat/completions。 */
    private static String resolveStreamUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + CHAT_COMPLETIONS_PATH;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必填配置: " + propertyName);
        }
        return value;
    }
}

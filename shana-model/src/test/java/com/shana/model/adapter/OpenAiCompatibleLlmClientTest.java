package com.shana.model.adapter;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 适配器的 HTTP 层测试：用 {@link MockRestServiceServer} 拦截请求，不发真实网络请求。
 *
 * <p>覆盖三件事：请求长得对不对、成功响应能不能翻译成统一模型、失败能不能翻译成统一异常。
 */
class OpenAiCompatibleLlmClientTest {

    private static final String REQUEST_URL = "https://llm.test/v1/chat/completions";

    private MockRestServiceServer server;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = OpenAiCompatibleLlmClient.create(properties(), builder);
    }

    @Test
    @DisplayName("请求路径、鉴权头、请求体都符合 OpenAI 协议")
    void shouldSendOpenAiCompatibleRequest() {
        server.expect(requestTo(REQUEST_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.temperature").value(0.7))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("你好"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"我是 Shana"}}],
                         "usage":{"prompt_tokens":8,"completion_tokens":16}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = client.chat(request());

        assertThat(response.content()).isEqualTo("我是 Shana");
        assertThat(response.provider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(response.model()).isEqualTo("test-model");
        assertThat(response.usage()).isEqualTo(new TokenUsage(8, 16));
        server.verify();
    }

    @Test
    @DisplayName("上游 4xx 时翻译成服务不可用，并保留原始异常")
    void shouldTranslateErrorStatus() {
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"message":"invalid api key"}}
                                """));

        assertThatThrownBy(() -> client.chat(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("401")
                .hasCauseInstanceOf(org.springframework.web.client.RestClientResponseException.class)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        server.verify();
    }

    @Test
    @DisplayName("连接失败时翻译成服务不可用")
    void shouldTranslateConnectionFailure() {
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.chat(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("连接模型服务失败")
                .hasCauseInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        server.verify();
    }

    @Test
    @DisplayName("base-url 缺失时启动期直接失败，而不是等到第一次调用")
    void shouldFailFastWhenBaseUrlMissing() {
        OpenAiCompatibleProperties broken =
                new OpenAiCompatibleProperties(" ", "test-key", "test-model", 0.7);

        assertThatThrownBy(() -> OpenAiCompatibleLlmClient.create(broken, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    private static ChatRequest request() {
        return new ChatRequest(
                LlmProvider.OPENAI_COMPATIBLE,
                null,
                List.of(ChatMessage.system("你是群聊助手"), ChatMessage.user("你好")),
                null,
                null);
    }

    private static OpenAiCompatibleProperties properties() {
        return new OpenAiCompatibleProperties("https://llm.test/v1", "test-key", "test-model", 0.7);
    }
}

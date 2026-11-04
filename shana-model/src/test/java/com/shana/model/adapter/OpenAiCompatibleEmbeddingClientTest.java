package com.shana.model.adapter;

import com.shana.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 向量化适配器的 HTTP 层测试：拦截请求，不发真实网络请求。
 */
class OpenAiCompatibleEmbeddingClientTest {

    private static final String REQUEST_URL = "https://embed.test/v1/embeddings";

    private MockRestServiceServer server;
    private OpenAiCompatibleEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // 维度 2、单批 2 条，便于构造"需要分批"的场景
        client = OpenAiCompatibleEmbeddingClient.create(
                new EmbeddingProperties("https://embed.test/v1", "test-key", "bge-test", 2, 2), builder);
    }

    @Test
    @DisplayName("请求路径、鉴权头与请求体符合 OpenAI 协议")
    void shouldSendOpenAiCompatibleRequest() {
        server.expect(requestTo(REQUEST_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("bge-test"))
                .andExpect(jsonPath("$.input[0]").value("第一段"))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":3,"total_tokens":3}}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("第一段"));

        assertThat(vectors).hasSize(1);
        assertThat(vectors.getFirst()).containsExactly(0.1f, 0.2f);
        assertThat(client.dimension()).isEqualTo(2);
        assertThat(client.model()).isEqualTo("bge-test");
    }

    @Test
    @DisplayName("超过 batchSize 时拆成多次请求，结果按原顺序拼接")
    void shouldSplitIntoBatches() {
        server.expect(requestTo(REQUEST_URL))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[1,1]},{"index":1,"embedding":[2,2]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(REQUEST_URL))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[3,3]}]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embed(List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(2)).containsExactly(3f, 3f);
        server.verify();
    }

    @Test
    @DisplayName("空输入直接返回空，不发请求")
    void shouldSkipEmptyInput() {
        assertThat(client.embed(List.of())).isEmpty();
        assertThat(client.embed(null)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("维度不符时报错，提示检查模型与配置")
    void shouldFailOnDimensionMismatch() {
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[1,2,3]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("a")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("维度");
    }

    @Test
    @DisplayName("上游 4xx 时翻译成统一异常，并提示可能是接口不支持")
    void shouldTranslateHttpError() {
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.embed(List.of("a")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("/embeddings");
    }
}

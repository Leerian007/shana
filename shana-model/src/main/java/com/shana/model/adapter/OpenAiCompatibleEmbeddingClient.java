package com.shana.model.adapter;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.EmbeddingClient;
import com.shana.model.adapter.dto.OpenAiEmbeddingRequest;
import com.shana.model.adapter.dto.OpenAiEmbeddingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * 面向 OpenAI 兼容 /embeddings 协议的向量化实现：OpenAI、硅基流动、阿里百炼、本地 vLLM 都能用。
 *
 * <p>三个刻意的设计：
 * <ul>
 *   <li><b>分批</b>：多数服务商限制单次 input 条数（16/32 不等），一条长文档动辄上百个 chunk，
 *       不分批会在中途被 400 拒绝，而且失败点取决于文档长度，很难复现。</li>
 *   <li><b>维度在启动时确定</b>：维度来自配置而不是第一次响应，见 {@link EmbeddingClient#dimension()}。</li>
 *   <li><b>异常翻译与对话适配器保持一致</b>：上游 4xx/5xx、连不上、解析失败分别给出不同提示，
 *       否则用户只会看到"AI 不工作了"。</li>
 * </ul>
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final EmbeddingProperties properties;
    private final RestClient restClient;
    private final int dimension;
    private final int batchSize;

    OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
        this.dimension = properties.dimensionOrDefault();
        this.batchSize = properties.batchSizeOrDefault();
    }

    public static OpenAiCompatibleEmbeddingClient create(EmbeddingProperties properties, RestClient.Builder builder) {
        String baseUrl = requireText(properties.baseUrl(), "shana.embedding.base-url");
        String apiKey = requireText(properties.apiKey(), "shana.embedding.api-key");
        String model = requireText(properties.model(), "shana.embedding.model");
        RestClient restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        log.info("已启用向量化模型: baseUrl={}, model={}, dimension={}", baseUrl, model, properties.dimensionOrDefault());
        return new OpenAiCompatibleEmbeddingClient(properties, restClient);
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            vectors.addAll(embedBatch(texts.subList(from, to)));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        OpenAiEmbeddingRequest body = OpenAiEmbeddingMapper.toRequest(properties.model(), batch);
        log.debug("调用向量化服务: model={}, batchSize={}", body.model(), batch.size());

        try {
            OpenAiEmbeddingResponse response = restClient.post()
                    .uri(EMBEDDINGS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(OpenAiEmbeddingResponse.class);
            return OpenAiEmbeddingMapper.toVectors(response, batch.size(), dimension);
        } catch (RestClientResponseException ex) {
            log.warn("向量化服务返回错误状态: status={}, model={}", ex.getStatusCode().value(), body.model());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "向量化服务返回错误状态 " + ex.getStatusCode().value()
                            + "（该服务是否提供 /embeddings？DeepSeek 等对话模型不提供此接口）", ex);
        } catch (ResourceAccessException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "连接向量化服务失败，请检查网络或 shana.embedding.base-url", ex);
        } catch (RestClientException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "调用向量化服务失败: " + ex.getMessage(), ex);
        }
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必填配置: " + propertyName);
        }
        return value;
    }
}

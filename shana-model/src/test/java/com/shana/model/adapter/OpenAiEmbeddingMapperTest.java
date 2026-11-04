package com.shana.model.adapter;

import com.shana.common.exception.BusinessException;
import com.shana.model.adapter.dto.OpenAiEmbeddingRequest;
import com.shana.model.adapter.dto.OpenAiEmbeddingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * /embeddings 协议转换测试。
 *
 * <p>这里的三条校验（条数、顺序、维度）在"能跑通"的演示里永远不会触发，
 * 一旦触发就表现为"检索结果莫名其妙"，所以必须有用例守着。
 */
class OpenAiEmbeddingMapperTest {

    @Test
    @DisplayName("请求体带上模型名与全部文本")
    void shouldBuildRequest() {
        OpenAiEmbeddingRequest request = OpenAiEmbeddingMapper.toRequest("bge-m3", List.of("第一段", "第二段"));

        assertThat(request.model()).isEqualTo("bge-m3");
        assertThat(request.input()).containsExactly("第一段", "第二段");
    }

    @Test
    @DisplayName("正常响应按入参顺序还原")
    void shouldParseVectors() {
        OpenAiEmbeddingResponse response = new OpenAiEmbeddingResponse(List.of(
                data(0, 1f, 2f),
                data(1, 3f, 4f)), null);

        List<float[]> vectors = OpenAiEmbeddingMapper.toVectors(response, 2, 2);

        assertThat(vectors).hasSize(2);
        assertThat(vectors.getFirst()).containsExactly(1f, 2f);
        assertThat(vectors.get(1)).containsExactly(3f, 4f);
    }

    @Test
    @DisplayName("上游乱序返回时按 index 还原，而不是按数组下标硬对")
    void shouldReorderByIndex() {
        OpenAiEmbeddingResponse response = new OpenAiEmbeddingResponse(List.of(
                data(1, 30f, 40f),
                data(0, 10f, 20f)), null);

        List<float[]> vectors = OpenAiEmbeddingMapper.toVectors(response, 2, 2);

        assertThat(vectors.get(0)).containsExactly(10f, 20f);
        assertThat(vectors.get(1)).containsExactly(30f, 40f);
    }

    @Test
    @DisplayName("索引不连续时报错：错位的向量比没有向量更危险")
    void shouldRejectBrokenIndexes() {
        OpenAiEmbeddingResponse response = new OpenAiEmbeddingResponse(List.of(
                data(0, 1f, 2f),
                data(2, 3f, 4f)), null);

        assertThatThrownBy(() -> OpenAiEmbeddingMapper.toVectors(response, 2, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("索引不连续");
    }

    @Test
    @DisplayName("条数不匹配时报错，不做补齐也不做截断")
    void shouldRejectSizeMismatch() {
        OpenAiEmbeddingResponse response = new OpenAiEmbeddingResponse(List.of(data(0, 1f, 2f)), null);

        assertThatThrownBy(() -> OpenAiEmbeddingMapper.toVectors(response, 2, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("条数不匹配");
    }

    @Test
    @DisplayName("维度与配置不一致时报错，并指出该查哪个配置")
    void shouldRejectDimensionMismatch() {
        OpenAiEmbeddingResponse response = new OpenAiEmbeddingResponse(List.of(data(0, 1f, 2f)), null);

        assertThatThrownBy(() -> OpenAiEmbeddingMapper.toVectors(response, 1, 1024))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("维度")
                .hasMessageContaining("shana.embedding.model");
    }

    @Test
    @DisplayName("空响应直接失败")
    void shouldRejectEmptyResponse() {
        assertThatThrownBy(() -> OpenAiEmbeddingMapper.toVectors(null, 1, 2))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OpenAiEmbeddingMapper.toVectors(
                new OpenAiEmbeddingResponse(List.of(), null), 1, 2))
                .isInstanceOf(BusinessException.class);
    }

    private static OpenAiEmbeddingResponse.OpenAiEmbeddingData data(int index, float... vector) {
        return new OpenAiEmbeddingResponse.OpenAiEmbeddingData(index, vector);
    }
}

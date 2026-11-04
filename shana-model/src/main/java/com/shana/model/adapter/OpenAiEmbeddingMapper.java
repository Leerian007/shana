package com.shana.model.adapter;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.adapter.dto.OpenAiEmbeddingRequest;
import com.shana.model.adapter.dto.OpenAiEmbeddingResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * /embeddings 的协议转换，保持包级可见的纯函数，便于单测。
 *
 * <p>这里做三件在"能跑通"之外容易被忽略的事：还原顺序、校验条数、校验维度。
 * 三者只要有一个不检查，问题就会以"检索结果莫名其妙"的形式出现在几天之后。
 */
final class OpenAiEmbeddingMapper {

    private OpenAiEmbeddingMapper() {
    }

    static OpenAiEmbeddingRequest toRequest(String model, List<String> texts) {
        return new OpenAiEmbeddingRequest(model, texts);
    }

    /**
     * 把响应还原成与入参一一对应的向量列表。
     *
     * @param expected  本批请求的文本条数
     * @param dimension 期望维度，来自配置
     */
    static List<float[]> toVectors(OpenAiEmbeddingResponse response, int expected, int dimension) {
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "向量化服务返回空结果");
        }

        List<OpenAiEmbeddingResponse.OpenAiEmbeddingData> data = new ArrayList<>(response.data());
        if (data.size() != expected) {
            // 少一条就整体丢弃：错位对应会让"这段文本"匹配到"另一段向量"，
            // 比直接失败更难发现
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "向量化结果条数不匹配：期望 " + expected + "，实际 " + data.size());
        }

        if (data.stream().allMatch(item -> item.index() != null)) {
            data.sort(Comparator.comparingInt(OpenAiEmbeddingResponse.OpenAiEmbeddingData::index));
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).index() != i) {
                    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                            "向量化结果索引不连续，无法对应入参顺序");
                }
            }
        }

        List<float[]> vectors = new ArrayList<>(data.size());
        for (OpenAiEmbeddingResponse.OpenAiEmbeddingData item : data) {
            float[] vector = item.embedding();
            if (vector == null || vector.length == 0) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "向量化服务返回了空向量");
            }
            if (vector.length != dimension) {
                // 维度不符说明模型或配置选错了，写进 vector(N) 列会直接被数据库拒绝，
                // 在这里就抛出来，错误信息能指到具体配置项
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "向量维度与配置不一致：配置 " + dimension + "，实际 " + vector.length
                                + "（检查 shana.embedding.model 与 dimension 是否匹配）");
            }
            vectors.add(vector);
        }
        return vectors;
    }
}

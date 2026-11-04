package com.shana.model;

import java.util.List;

/**
 * 文本向量化入口（Embedding）。
 *
 * <p>与 {@link LlmClient} 并列而不是合并进去：对话模型与嵌入模型经常来自不同厂商
 * （DeepSeek 只提供对话，向量化通常走别家），把两者塞进一个接口会让"换一半"变成"换全部"。
 *
 * <p>实现约定：
 * <ul>
 *   <li>线程安全，允许并发调用；</li>
 *   <li>返回顺序必须与入参顺序严格一致；</li>
 *   <li>失败时抛出 {@link com.shana.common.exception.BusinessException}，
 *       错误码 {@code SERVICE_UNAVAILABLE}。</li>
 * </ul>
 */
public interface EmbeddingClient {

    /** 嵌入模型名，例如 {@code BAAI/bge-m3}。用于日志与排错。 */
    String model();

    /**
     * 向量维度。
     *
     * <p>这个值必须与数据库列 {@code vector(N)} 一致，否则插入会直接失败。
     * 之所以要求实现类显式给出而不是从第一次调用里推断：维度是建表时就定好的，
     * 拿到第一个响应才发现对不上，那时表已经建错了。
     */
    int dimension();

    /** 批量向量化，返回顺序与 {@code texts} 一一对应。 */
    List<float[]> embed(List<String> texts);
}

package com.shana.model;

/**
 * 统一的模型调用入口。
 * <p>业务代码只依赖本接口，不依赖任何厂商 SDK 或 HTTP 细节。
 * <p>实现类注册为 Spring Bean 后，{@link LlmClientRegistry} 会自动按 {@link #provider()} 归类，
 * 因此新增一个模型来源只需要新增一个实现类。
 *
 * <p>实现约定：
 * <ul>
 *   <li>必须是线程安全的，允许被并发调用；</li>
 *   <li>底层失败时抛出 {@link com.shana.common.exception.BusinessException}，
 *       错误码使用 {@code SERVICE_UNAVAILABLE}，并保留原始异常作为 cause。</li>
 * </ul>
 */
public interface LlmClient {

    /** 本实现负责的模型提供方，全局唯一。 */
    LlmProvider provider();

    /** 执行一次对话补全。 */
    ChatResponse chat(ChatRequest request);
}

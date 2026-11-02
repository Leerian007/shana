package com.shana.model;

/**
 * 模型服务提供方类型。
 * <p>业务代码只面向该枚举编程；新增一个厂商时，扩展枚举 + 新增一个 {@link LlmClient} 实现即可，
 * 不需要修改任何业务逻辑。
 */
public enum LlmProvider {

    /** 云端 OpenAI 兼容接口（OpenAI、DeepSeek、通义、vLLM 等都提供 /v1/chat/completions） */
    OPENAI_COMPATIBLE,

    /** 本地 Ollama 服务 */
    OLLAMA,

    /** 自行 LoRA / QLoRA 微调后，通过本地推理服务（如 vLLM、llama.cpp server）暴露的模型 */
    LOCAL_FINE_TUNED,

    /** 其它第三方服务 */
    CUSTOM
}

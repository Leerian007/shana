/**
 * 模型适配层：放置 {@link com.shana.model.LlmClient} 的具体实现。
 *
 * <p>当前阶段刻意不提供实现，只约定扩展位置。后续按需添加：
 * <ul>
 *   <li>{@code OpenAiCompatibleLlmClient} —— 覆盖 OpenAI、DeepSeek、通义、vLLM 等兼容接口；</li>
 *   <li>{@code OllamaLlmClient} —— 本地 Ollama；</li>
 *   <li>{@code LocalFineTunedLlmClient} —— 自建 LoRA/QLoRA 推理服务（通常也是 OpenAI 兼容协议）。</li>
 * </ul>
 *
 * <p>每个实现只做三件事：协议转换、HTTP 调用、异常翻译，不承载任何业务逻辑。
 */
package com.shana.model.adapter;

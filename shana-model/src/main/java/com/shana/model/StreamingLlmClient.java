package com.shana.model;

import java.util.function.Consumer;

/**
 * 具备流式能力的模型客户端。
 *
 * <p>刻意做成<b>独立接口</b>而不是在 {@link LlmClient} 上加默认方法：
 * 默认方法只能"假装支持然后抛异常"，调用方无法在调用前判断能力；
 * 用 {@code instanceof} 判断则是编译期就明确的语义——不支持就是另一个接口。
 * Agent 因此可以在同一个循环里选择"流式提问"或"整段提问 + 一次性推送"。
 *
 * <p>约定：{@code onText} 会在<b>当前线程</b>上被逐段回调，实现方不需要自行调度。
 * 回调抛出的异常会中断整次调用并向上冒泡——客户端断开时正是靠这一点及时止损。
 */
public interface StreamingLlmClient extends LlmClient {

    /**
     * 执行一次流式对话补全。
     *
     * <p>返回值的语义与 {@link LlmClient#chat(ChatRequest)} 完全一致：
     * 正文是全量拼接后的结果，工具调用是把碎片增量拼装完成的完整对象。
     * 也就是说，<b>流式只改变"什么时候拿到"，不改变"拿到什么"</b>，
     * 上层因此不需要维护两套解析逻辑。
     *
     * @param request 请求
     * @param onText  正文增量回调，可能被调用 0 次（模型直接要求调工具时）
     * @return 聚合后的完整响应
     */
    ChatResponse streamChat(ChatRequest request, Consumer<String> onText);
}

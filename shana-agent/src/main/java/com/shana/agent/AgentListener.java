package com.shana.agent;

/**
 * Agent 执行过程中的回调，用于"边生成边给"。
 *
 * <p>为什么需要 {@link #onRoundStart(int)} 这个看起来多余的钩子：
 * 一次回答可能有多轮模型调用（模型先说一句"我查一下"，然后调工具，再正式作答）。
 * 那句过渡语气在流式模式下<b>已经推给客户端了</b>，但它不是最终答复、也不会落库。
 * 有了轮次边界，客户端才能在第二轮开始前清掉这段过渡文本，
 * 从而保证"用户看到的"和"系统记住的"是同一段话。
 *
 * <p>所有方法都有默认空实现：不关心某个事件的调用方不需要写一堆空方法。
 * 回调运行在<b>调用线程</b>上，实现方不要在其中做耗时操作——它会直接拖慢 token 的吐出速度。
 */
public interface AgentListener {

    /** 不关心任何事件的监听器，用于非流式调用。 */
    AgentListener NOOP = new AgentListener() {
    };

    /**
     * 即将开始第 {@code round} 轮模型调用（从 1 开始）。
     * <p>第二轮及以后意味着上一轮的文字只是过渡，不是答案。
     */
    default void onRoundStart(int round) {
    }

    /** 收到一段正文增量。 */
    default void onText(String delta) {
    }

    /** 即将执行一次工具调用，参数是工具名。 */
    default void onToolCall(String toolName) {
    }
}

package com.shana.core.chat;

/**
 * 流式问答的过程回调。
 *
 * <p>它是"问答用例"对外的输出端口，而不是传输层协议：这里没有 SSE、没有事件名、没有心跳。
 * 把 HTTP 细节留在 Controller 的适配器里，是为了让换传输方式（WebSocket、gRPC stream）
 * 时不用碰业务编排；同样地，core 也不需要知道"客户端断开了"该回什么状态码。
 *
 * <p>回调在<b>处理线程</b>上同步执行，实现方抛出的异常会中断本次生成。
 * 客户端断开正是靠这一点止损的——上层抛一个运行时异常上来，
 * 模型客户端会立刻关闭上游连接，不再为一个没人接收的响应烧 token。
 */
public interface ChatStreamHandler {

    /** 不关心中间过程时的空实现。 */
    ChatStreamHandler NOOP = new ChatStreamHandler() {
    };

    /**
     * 新一轮模型调用开始（从 1 开始）。
     * <p>大于 1 表示之前输出的文字只是过渡语，不是最终答复：接收方应当丢弃已展示的内容。
     */
    default void onRoundStart(int round) {
    }

    /** 收到一段正文增量。 */
    default void onDelta(String text) {
    }

    /** 即将执行一次工具调用。用来让用户在等待工具结果时知道"系统正在做什么"。 */
    default void onTool(String toolName) {
    }

    /**
     * 生成完毕，且会话记忆已经写好。
     *
     * <p>顺序很重要：先落库再回调，客户端收到 {@code onComplete} 时下一轮对话一定能接上。
     * 反过来先通知后落库的话，用户看到回答立刻追问，可能撞上"摘要还没写完"的窗口。
     */
    default void onComplete(ChatReply reply) {
    }
}

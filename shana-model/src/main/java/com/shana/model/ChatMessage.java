package com.shana.model;

import java.util.List;
import java.util.Objects;

/**
 * 一条对话消息。
 *
 * <p>这个类型同时承担两个角色，因此有两组字段的"生命周期"完全不同：
 * <ul>
 *   <li><b>发给模型</b>：{@code role}、{@code content}，以及工具调用协议需要的
 *       {@code toolCalls}、{@code toolCallId}；</li>
 *   <li><b>只落库</b>：{@code senderId}（群成员 ID），仅用于按人聚合、画像与月度总结，
 *       传给模型时会被丢弃——模型不知道"谁"说的，也不需要知道。</li>
 * </ul>
 * 反过来，工具协议字段<b>只存在于一次问答的内存里，不落库</b>：
 * tool 消息脱离它上一条带 {@code tool_calls} 的 assistant 消息就毫无意义，
 * 而对话记忆按条读取、窗口会截断，把两者拆散后下一次请求必然被上游拒绝
 * （"messages with role 'tool' must be a response to a preceding message with 'tool_calls'"）。
 *
 * @param role       角色（模型协议概念：谁在"扮演"什么）
 * @param senderId   群成员标识，仅用于落库分析，不进 prompt
 * @param content    消息正文；携带工具调用请求的 assistant 消息可以为 {@code null}
 * @param toolCallId 仅 role=tool 时有值，指回被响应的那次调用
 * @param toolCalls  仅 role=assistant 时可能有值，表示"模型要求调这些工具"
 */
public record ChatMessage(ChatRole role, String senderId, String content,
                          String toolCallId, List<ToolCall> toolCalls) {

    public ChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        // null 统一成空列表：调用方不必写 toolCalls == null 的判断，equals 也更好比较
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (content == null && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("content 不能为空，除非是携带 toolCalls 的 assistant 消息");
        }
    }

    /** 不带发送者、不带工具协议的消息（system / assistant，以及尚未接入成员体系的调用方）。 */
    public ChatMessage(ChatRole role, String content) {
        this(role, null, content, null, List.of());
    }

    /** 从存储读出的普通消息：只有角色、发送者与正文，工具协议字段本来就不落库。 */
    public ChatMessage(ChatRole role, String senderId, String content) {
        this(role, senderId, content, null, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, null, content, null, List.of());
    }

    /** 某位群成员发出的消息，带发送者标识。 */
    public static ChatMessage user(String senderId, String content) {
        return new ChatMessage(ChatRole.USER, senderId, content, null, List.of());
    }

    /** 不知道发送者时使用（例如历史数据、离线导入）。 */
    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, null, content, null, List.of());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, null, content, null, List.of());
    }

    /**
     * 模型要求调用工具的 assistant 消息，必须紧跟着同样数量的 tool 消息一起回传，
     * 否则上游会拒绝整段对话。
     *
     * @param content   模型同时给出的文字，通常为 null
     * @param toolCalls 本轮要执行的工具调用，不可为空
     */
    public static ChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("assistantToolCalls 至少需要一个工具调用");
        }
        return new ChatMessage(ChatRole.ASSISTANT, null, content, null, toolCalls);
    }

    /** 工具执行结果，{@code toolCallId} 必须是模型给出的调用标识。 */
    public static ChatMessage toolResult(String toolCallId, String content) {
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        return new ChatMessage(ChatRole.TOOL, null, content, toolCallId, List.of());
    }
}

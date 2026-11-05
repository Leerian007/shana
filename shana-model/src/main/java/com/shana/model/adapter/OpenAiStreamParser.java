package com.shana.model.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatResponse;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import com.shana.model.ToolCall;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 逐行解析 OpenAI 兼容协议的流式响应（SSE）。
 *
 * <p>把"一堆 {@code data: {...}} 文本帧"还原成一个和普通响应完全同形的 {@link ChatResponse}，
 * 这样 Agent 与业务层不需要为流式再写一套逻辑——流式只改变数据<b>何时</b>到达，不改变它<b>是什么</b>。
 *
 * <p>三个必须处理对的细节，也是最容易写错的地方：
 * <ul>
 *   <li><b>工具调用是碎片拼出来的。</b>上游按 {@code index} 分片下发，第一片带 {@code id} 与
 *       {@code name}，后续片只带 {@code arguments} 的半截 JSON 字符串。必须按 index 归组再拼接，
 *       否则模型只要调两次工具就会串味。同一个 {@code id}/{@code name} 重复下发是合法行为
 *       （部分服务每片都重发），所以只在为空时赋值；</li>
 *   <li><b>用量只在收尾时单独发一次。</b>而且它的数据帧里 {@code choices} 是空数组。
 *       解析时不能因为"没有 choices"就当空帧跳过，否则 token 统计会全部丢失；</li>
 *   <li><b>心跳与注释行必须忽略。</b>上游为了防代理超时会发 {@code : keep-alive}，
 *       也有服务在首帧发 {@code event: message}，这些都不是正文。</li>
 * </ul>
 *
 * <p>本类不是线程安全的，一次调用配一个实例。
 */
final class OpenAiStreamParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_MARK = "[DONE]";

    private final Consumer<String> onText;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();

    private TokenUsage usage = TokenUsage.unknown();
    private String finishReason;
    private boolean done;

    OpenAiStreamParser(Consumer<String> onText) {
        this.onText = onText == null ? text -> { } : onText;
    }

    /**
     * 喂入一行原始文本（不含换行符）。非数据行会被安静忽略。
     *
     * @throws BusinessException 数据帧不是合法 JSON，或上游在流中下发 {@code error} 对象时抛出
     */
    void accept(String rawLine) {
        if (done || rawLine == null) {
            return;
        }
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith(":")) {
            return;
        }
        if (!line.startsWith(DATA_PREFIX)) {
            // event: / id: / retry: 等字段对结果没有影响
            return;
        }

        String payload = line.substring(DATA_PREFIX.length()).strip();
        if (payload.isEmpty()) {
            return;
        }
        if (DONE_MARK.equals(payload)) {
            done = true;
            return;
        }
        acceptPayload(payload);
    }

    boolean isDone() {
        return done;
    }

    /** 上游给出的结束原因，{@code length} 表示被 max_tokens 截断。 */
    String finishReason() {
        return finishReason;
    }

    /**
     * 汇总成统一响应。
     *
     * @throws BusinessException 整段流既没有正文也没有工具调用时抛出
     */
    ChatResponse toResponse(String model, LlmProvider provider) {
        List<ToolCall> calls = toolCalls.values().stream()
                .map(ToolCallAccumulator::toToolCall)
                .filter(Objects::nonNull)
                .toList();
        String text = content.isEmpty() ? null : content.toString();
        if (text == null && calls.isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型流式响应未返回任何内容");
        }
        return new ChatResponse(text, provider, model, usage, calls);
    }

    private void acceptPayload(String payload) {
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型流式响应不是合法 JSON", e);
        }
        if (root == null || root.isMissingNode()) {
            return;
        }

        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            // 有的服务（例如 Azure、部分网关）遇到限流时不是返回 429，而是在 200 的流里塞一个 error 帧
            String message = textOf(error, "message");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "模型流式响应返回错误: " + (message == null ? "上游未提供原因" : message));
        }

        // 先读 usage：收尾帧的 choices 是空数组，跳过 choices 也不能漏掉用量
        readUsage(root.get("usage"));

        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);
        if (choice == null || choice.isNull()) {
            return;
        }

        String reason = textOf(choice, "finish_reason");
        if (reason != null && !reason.isBlank()) {
            finishReason = reason;
        }

        JsonNode delta = choice.get("delta");
        if (delta == null || delta.isNull()) {
            return;
        }

        // reasoning_content（DeepSeek R1 等）刻意不读：思考过程不该被当成回答推给用户
        String text = textOf(delta, "content");
        if (text != null && !text.isEmpty()) {
            content.append(text);
            onText.accept(text);
        }

        JsonNode calls = delta.get("tool_calls");
        if (calls == null || !calls.isArray()) {
            return;
        }
        for (JsonNode call : calls) {
            int index = call.path("index").asInt(0);
            JsonNode function = call.get("function");
            toolCalls.computeIfAbsent(index, key -> new ToolCallAccumulator())
                    .merge(textOf(call, "id"),
                            function == null ? null : textOf(function, "name"),
                            function == null ? null : textOf(function, "arguments"));
        }
    }

    private void readUsage(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        usage = new TokenUsage(node.path("prompt_tokens").asInt(0), node.path("completion_tokens").asInt(0));
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 按 index 累积一次工具调用的三部分：id、函数名、参数 JSON 片段。 */
    private static final class ToolCallAccumulator {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(String fragmentId, String fragmentName, String fragmentArguments) {
            // id 与 name 在每个分片里重复下发是合法的，只在缺失时赋值，避免拼成 "echoecho"
            if (id == null && fragmentId != null && !fragmentId.isBlank()) {
                id = fragmentId;
            }
            if (name == null && fragmentName != null && !fragmentName.isBlank()) {
                name = fragmentName;
            }
            if (fragmentArguments != null) {
                // 参数一定是逐片追加：{"days": + 7} 两片合起来才是完整 JSON
                arguments.append(fragmentArguments);
            }
        }

        ToolCall toToolCall() {
            if (name == null) {
                // 没有函数名说明这一组碎片不完整（连接被中断），执行它只会得到一个莫名其妙的错误
                return null;
            }
            String json = arguments.isEmpty() ? "{}" : arguments.toString();
            return new ToolCall(id, name, json);
        }
    }
}

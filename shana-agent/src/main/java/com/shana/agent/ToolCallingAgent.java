package com.shana.agent;

import com.shana.agent.tool.Tool;
import com.shana.agent.tool.ToolContext;
import com.shana.agent.tool.ToolRegistry;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmClient;
import com.shana.model.LlmClientRegistry;
import com.shana.model.TokenUsage;
import com.shana.model.ToolCall;
import com.shana.model.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工具调用编排：模型要调工具就执行，把结果回填，再问一次，直到模型给出最终答复。
 *
 * <p>单轮和多轮是同一段代码——差别只在循环转了几圈。这一点很重要：
 * "单轮工具调用"如果单独写一条路径，多轮就要重写一遍，而两边的错误处理、
 * 消息拼装规则稍有出入，就会变成"单轮好好的，一旦需要查两次就报错"。
 *
 * <p>四个明确的取舍：
 * <ul>
 *   <li><b>工具失败不中断对话</b>：执行异常转成一条文本回填给模型，由它决定怎么向用户解释。
 *       工具抛异常就整段失败，等于模型一调错工具用户就收不到回复；</li>
 *   <li><b>轮次有上限</b>：模型可能反复调工具不收敛。到上限后<b>不带工具</b>再问一次，
 *       逼它用已有结果作答——直接抛错会让用户白等一场却什么都没拿到；</li>
 *   <li><b>用量跨轮累加</b>：一次问答可能发生 3 次模型调用，只报最后一轮会低估真实成本；</li>
 *   <li><b>上下文由调用方传入</b>：会话 ID 不从模型参数里读，工具无法越界查别的群。</li>
 * </ul>
 *
 * <p>不做超时：工具是进程内调用，超时该由工具自己（HTTP 客户端、SQL statement timeout）负责。
 * 在这里套一层 Future 只能解决"等"，解决不了"卡住的线程还在占资源"。
 */
@Component
public class ToolCallingAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingAgent.class);

    /**
     * 单条工具结果的长度上限。
     * <p>工具可能返回几万字的查询结果，原样塞回去会挤掉对话历史、推高成本，
     * 甚至直接超出上下文窗口——那时用户收到的是"上下文超长"而不是答案。
     */
    private static final int MAX_RESULT_CHARS = 4000;

    /** 回填给模型的错误信息长度上限，避免异常 message 里带出整段 SQL 或堆栈。 */
    private static final int MAX_ERROR_CHARS = 300;

    private final LlmClientRegistry llmClientRegistry;
    private final ToolRegistry toolRegistry;
    private final boolean enabled;
    private final int maxRounds;

    public ToolCallingAgent(LlmClientRegistry llmClientRegistry,
                            ToolRegistry toolRegistry,
                            @Value("${shana.agent.enabled:true}") boolean enabled,
                            @Value("${shana.agent.max-rounds:4}") int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("shana.agent.max-rounds 必须大于 0");
        }
        this.llmClientRegistry = llmClientRegistry;
        this.toolRegistry = toolRegistry;
        this.enabled = enabled;
        this.maxRounds = maxRounds;
    }

    @Override
    public AgentResult run(AgentRequest request) {
        LlmClient client = llmClientRegistry.get(request.provider());
        // 关掉开关或一个工具都没注册时，specs 为空，下面的循环转一圈就返回——不需要单独写"纯对话"分支
        List<ToolSpec> specs = enabled ? toolRegistry.specs() : List.of();
        if (!enabled && !toolRegistry.isEmpty()) {
            log.info("工具调用已关闭（shana.agent.enabled=false），本次对话不提供任何工具");
        }

        List<ChatMessage> messages = new ArrayList<>(request.messages());
        TokenUsage usage = TokenUsage.unknown();
        List<String> toolsUsed = new ArrayList<>();

        for (int round = 1; round <= maxRounds; round++) {
            ChatResponse response = client.chat(
                    new ChatRequest(request.provider(), null, messages, null, null, specs));
            usage = add(usage, response.usage());

            if (!response.hasToolCalls()) {
                return new AgentResult(requireContent(response.content()), usage, toolsUsed, round);
            }

            // assistant 的 tool_calls 消息与随后每条 tool 结果必须成对出现，顺序和 id 都不能变
            messages.add(ChatMessage.assistantToolCalls(response.content(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                toolsUsed.add(call.name());
                messages.add(ChatMessage.toolResult(call.id(), invoke(call, request.context())));
            }
            log.debug("第 {} 轮工具调用: conversationId={}, tools={}",
                    round, request.context().conversationId(),
                    response.toolCalls().stream().map(ToolCall::name).toList());
        }

        log.warn("工具调用达到轮次上限，改为不带工具再问一次: conversationId={}, rounds={}, tools={}",
                request.context().conversationId(), maxRounds, toolsUsed);
        ChatResponse last = client.chat(
                new ChatRequest(request.provider(), null, messages, null, null, List.of()));
        usage = add(usage, last.usage());
        if (last.hasToolCalls()) {
            // 已经告诉模型"没有工具可用"它还要求调用，说明模型或上游行为异常，重试没有意义
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型在禁用工具后仍要求调用工具，请稍后重试");
        }
        return new AgentResult(requireContent(last.content()), usage, toolsUsed, maxRounds + 1);
    }

    /**
     * 执行一次工具调用，任何失败都转成文本回填。
     *
     * <p>注意这里<b>吞掉</b>了异常而不是向上抛：工具报错属于"这一轮信息不足"，
     * 模型完全可以换个参数重试，或者直接告诉用户"查不到"。
     */
    private String invoke(ToolCall call, ToolContext context) {
        Optional<Tool> tool = toolRegistry.find(call.name());
        if (tool.isEmpty()) {
            // 模型编出一个不存在的工具是常见现象（尤其是工具多、说明写得模糊时），
            // 把可用清单回给它，下一轮往往就能调到正确的那个
            log.warn("模型请求了不存在的工具: name={}", call.name());
            return "错误：没有名为 " + call.name() + " 的工具。可用工具：" + String.join("、", toolRegistry.names());
        }

        try {
            String result = tool.get().execute(context, call.arguments());
            if (result == null || result.isBlank()) {
                return "工具执行成功，但没有任何内容返回。";
            }
            return truncate(result);
        } catch (RuntimeException e) {
            log.warn("工具执行失败: name={}, cause={}", call.name(), e.toString());
            return "错误：工具 " + call.name() + " 执行失败 - " + describe(e);
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        String detail = (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
        return detail.length() <= MAX_ERROR_CHARS ? detail : detail.substring(0, MAX_ERROR_CHARS) + "…";
    }

    private static String truncate(String result) {
        if (result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        return result.substring(0, MAX_RESULT_CHARS) + "\n…（结果过长，已截断）";
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型服务返回了空回复");
        }
        return content;
    }

    private static TokenUsage add(TokenUsage total, TokenUsage delta) {
        if (delta == null) {
            return total;
        }
        return new TokenUsage(total.promptTokens() + delta.promptTokens(),
                total.completionTokens() + delta.completionTokens());
    }
}

package com.shana.core.chat;

import com.shana.memory.ConversationMemory;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答链路编排：写上下文 → 组装 prompt → 调模型 → 回写上下文。
 *
 * <p>本类刻意不出现任何厂商概念（HTTP、API key、JSON 字段），它只知道"有记忆、有模型"，
 * 因此后续接入 Ollama 或自建微调模型时这里一行都不用改。
 *
 * <p>放在 {@code chat} 包下、与接口同级，和 health 包的写法保持一致。
 */
@Service
public class ChatServiceImpl implements ChatService {

    /**
     * 第一阶段固定人设，后续按群聊配置（例如从数据库读取每个群的助手设定）。
     * <p>放在 system 角色里而不是拼进用户消息，是为了让上下文更干净、也更省钱。
     */
    private static final String SYSTEM_PROMPT = """
            你是 Shana，一个在群聊中参与讨论的中文 AI 助手。
            回答简洁、直接，不复述别人刚说过的话；不确定时明确说明，不要编造。""";

    private final ConversationMemory conversationMemory;
    private final LlmClientRegistry llmClientRegistry;
    private final LlmProvider provider;

    public ChatServiceImpl(ConversationMemory conversationMemory,
                           LlmClientRegistry llmClientRegistry,
                           @Value("${shana.llm.provider:OPENAI_COMPATIBLE}") LlmProvider provider) {
        this.conversationMemory = conversationMemory;
        this.llmClientRegistry = llmClientRegistry;
        this.provider = provider;
    }

    @Override
    public ChatReply reply(String conversationId, String senderId, String message) {
        // 1. 先写入用户消息：即使本次调用失败，用户说过的话也留在上下文里，下一轮仍能接上。
        //    senderId 只是随消息落库的旁路信息，下面组装 prompt 时不会用到它
        conversationMemory.append(conversationId, ChatMessage.user(senderId, message));

        // 2. 组装 prompt：system 人设 + 本会话最近历史（history 已包含刚写入的这条，顺序为时间正序）
        List<ChatMessage> history = conversationMemory.history(conversationId);
        List<ChatMessage> messages = new ArrayList<>(history.size() + 1);
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.addAll(history);

        // 3. 调用模型。model / temperature 传 null，由适配器回落到自己的配置，业务层不关心厂商参数
        ChatResponse response = llmClientRegistry.get(provider)
                .chat(new ChatRequest(provider, null, messages, null, null));

        // 4. 回写助手回复，供下一轮对话使用
        conversationMemory.append(conversationId, ChatMessage.assistant(response.content()));

        return new ChatReply(conversationId, response.content(), response.usage());
    }
}

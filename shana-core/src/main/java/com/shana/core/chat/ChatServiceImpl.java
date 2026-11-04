package com.shana.core.chat;

import com.shana.core.memory.ConversationSummarizer;
import com.shana.core.memory.MemoryPromptRenderer;
import com.shana.memory.ConversationMemory;
import com.shana.memory.LongTermMemory;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答链路编排：写上下文 → 拼长期记忆 → 组装 prompt → 调模型 → 回写上下文 → 触发摘要。
 *
 * <p>本类刻意不出现任何厂商概念（HTTP、API key、JSON 字段），它只知道"有记忆、有模型"，
 * 因此后续接入 Ollama 或自建微调模型时这里一行都不用改。
 *
 * <p>长期记忆与摘要生成都是<b>可选依赖</b>：Redis 模式、内存模式下它们不存在，
 * 链路照常工作，只是没有跨窗口的记忆。用 {@link ObjectProvider} 而不是必填构造参数，
 * 就是为了让"没有记忆"是一种正常状态，而不是启动失败。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /**
     * 第一阶段固定人设，后续按群聊配置（例如从数据库读取每个群的助手设定）。
     * <p>放在 system 角色里而不是拼进用户消息，是为了让上下文更干净、也更省钱。
     */
    private static final String SYSTEM_PROMPT = """
            你是 Shana，一个在群聊中参与讨论的中文 AI 助手。
            回答简洁、直接，不复述别人刚说过的话；不确定时明确说明，不要编造。""";

    /**
     * 拼进 prompt 的事实条数上限。
     * <p>库里可能积累了几百条，全塞进去既费钱又会稀释真正相关的信息；
     * 取最新的若干条，配合滚动摘要已经够用。真正的相关性排序留给后续的检索。
     */
    private static final int MAX_PROMPT_FACTS = 10;

    private final ConversationMemory conversationMemory;
    private final LlmClientRegistry llmClientRegistry;
    private final ObjectProvider<LongTermMemory> longTermMemory;
    private final ObjectProvider<ConversationSummarizer> summarizer;
    private final LlmProvider provider;

    public ChatServiceImpl(ConversationMemory conversationMemory,
                           LlmClientRegistry llmClientRegistry,
                           ObjectProvider<LongTermMemory> longTermMemory,
                           ObjectProvider<ConversationSummarizer> summarizer,
                           @Value("${shana.llm.provider:OPENAI_COMPATIBLE}") LlmProvider provider) {
        this.conversationMemory = conversationMemory;
        this.llmClientRegistry = llmClientRegistry;
        this.longTermMemory = longTermMemory;
        this.summarizer = summarizer;
        this.provider = provider;
    }

    @Override
    public ChatReply reply(String conversationId, String senderId, String message) {
        // 1. 先写入用户消息：即使本次调用失败，用户说过的话也留在上下文里，下一轮仍能接上。
        //    senderId 只是随消息落库的旁路信息，下面组装 prompt 时不会用到它
        conversationMemory.append(conversationId, ChatMessage.user(senderId, message));

        // 2. 组装 prompt：system 人设 + 长期记忆 + 本会话最近历史（history 已含刚写入的这条，时间正序）
        List<ChatMessage> history = conversationMemory.history(conversationId);
        List<ChatMessage> messages = new ArrayList<>(history.size() + 2);
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        appendLongTermMemory(conversationId, messages);
        messages.addAll(history);

        // 3. 调用模型。model / temperature 传 null，由适配器回落到自己的配置，业务层不关心厂商参数
        ChatResponse response = llmClientRegistry.get(provider)
                .chat(new ChatRequest(provider, null, messages, null, null));

        // 4. 回写助手回复，供下一轮对话使用
        conversationMemory.append(conversationId, ChatMessage.assistant(response.content()));

        // 5. 顺带触发一次后台摘要。是否真的需要摘要在方法内部判断，
        //    这里不阻塞、不抛异常——记忆更新失败不能影响用户拿到回复
        triggerSummarize(conversationId);

        return new ChatReply(conversationId, response.content(), response.usage());
    }

    private void appendLongTermMemory(String conversationId, List<ChatMessage> messages) {
        LongTermMemory memory = longTermMemory.getIfAvailable();
        if (memory == null) {
            return;
        }

        String block = MemoryPromptRenderer.render(memory.load(conversationId, MAX_PROMPT_FACTS));
        if (!block.isBlank()) {
            // 用独立的 system 消息而不是拼进人设：摘要每次都会变，混在人设里
            // 会让"助手是谁"和"助手记得什么"两件事缠在一起，也不好按需整段丢弃
            messages.add(ChatMessage.system(block));
        }
    }

    private void triggerSummarize(String conversationId) {
        ConversationSummarizer conversationSummarizer = summarizer.getIfAvailable();
        if (conversationSummarizer == null) {
            return;
        }
        try {
            conversationSummarizer.summarizeAsync(conversationId);
        } catch (RuntimeException e) {
            // 异步任务提交失败（线程池队列满）不应该冒泡到调用方。
            // 摘要游标没有推进，下一次对话会重新触发，不会丢数据
            log.warn("提交后台摘要任务失败，本次跳过: conversationId={}, cause={}", conversationId, e.toString());
        }
    }
}

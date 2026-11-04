package com.shana.core.memory;

import com.shana.memory.ArchiveMessage;
import com.shana.memory.ConversationArchive;
import com.shana.memory.LongTermMemory;
import com.shana.memory.MemorySnapshot;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.ChatRole;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把"最近一段没人消化的对话"压缩成长期记忆：滚动摘要 + 关键信息。
 *
 * <p>几个关键取舍：
 *
 * <p><b>增量而不是全量重算。</b>每次只把"已有摘要 + 游标之后的一批消息"喂给模型。
 * 全量重算看似更准，但成本随对话量线性上涨，一个活跃群跑两周就喂不进去了。
 *
 * <p><b>摘要会丢细节，所以另有事实条目。</b>滚动摘要天然是有损压缩，
 * 反复滚动之后早期细节必然消失；关键信息是追加式的，不参与滚动，
 * 因此"张三在学 Flink"这类事实不会因为后面聊了几百句就蒸发。
 *
 * <p><b>游标即乐观锁。</b>同一群可能被两个实例同时摘要，只有把
 * {@code covered_message_id} 从读到的旧值推进成功的那一个才落库，
 * 另一个的结果直接丢弃——避免旧摘要覆盖新摘要。代价是偶尔白跑一次模型调用，
 * 但这远好过数据错乱。
 *
 * <p><b>绝不向主链路抛异常。</b>摘要失败只记日志，下一次对话还会重新触发。
 * 记忆是增强能力，不能因为它挂了就让用户发不出消息。
 */
@Service
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);

    /**
     * 摘要用的 system prompt。
     *
     * <p>逐条写死约束而不是"请总结一下"：不加约束时模型倾向于复述原话、
     * 把寒暄也写进去、并且给每条事实编一个"重要性"分数。
     */
    private static final String SYSTEM_PROMPT = """
            你负责把群聊记录整理成长期记忆。输入是某个群已有的摘要和新增对话，输出更新后的摘要与关键信息。

            要求：
            1. summary 不超过 300 字，只写群里发生过什么：讨论的话题、得出的结论、约定的时间与事项、还没解决的问题。
               保留已有摘要里仍然有效的内容，被新对话推翻的要更新，明确过时的（例如已经过去的日期）可以删掉。
               不写寒暄、不评价语气、不编造对话里没有的内容。
            2. facts 最多 8 条，每条不超过 30 字，只写跨对话仍然成立的信息。
               kind 只能取 PERSON（某人的身份或在做的事）、PREFERENCE（稳定的偏好或习惯）、
               DECISION（约定或决定）、FACT（其它客观信息）。
               subject 填对话里出现的人名；没出现名字时填空字符串。
               寒暄、临时情绪、只在当下有意义的内容不要写。
            3. 发言者以 u-xxxx 形式标识，摘要里不要直接写这个标识，用"有成员""有人"指代。
            4. 助手自己的发言不作为事实来源，它说的内容可能是错的。

            只输出 JSON，不要任何解释：
            {"summary":"...","facts":[{"kind":"PERSON","subject":"张三","content":"在学 Flink"}]}
            """;

    /** 摘要任务用更低的温度：这类结构化输出需要稳定，不需要"创意" */
    private static final double TEMPERATURE = 0.2;

    /** 摘要 + 若干条事实，通常 300~600 token 就够；给足余量但别让模型长篇大论 */
    private static final int MAX_TOKENS = 900;

    private final ObjectProvider<ConversationArchive> archiveProvider;
    private final ObjectProvider<LongTermMemory> longTermMemoryProvider;
    private final LlmClientRegistry llmClientRegistry;
    private final SummaryDraftParser parser = new SummaryDraftParser();
    private final int triggerMessages;
    private final int batchSize;
    private final LlmProvider provider;

    public ConversationSummarizer(ObjectProvider<ConversationArchive> archiveProvider,
                                  ObjectProvider<LongTermMemory> longTermMemoryProvider,
                                  LlmClientRegistry llmClientRegistry,
                                  @Value("${shana.memory.summary.trigger-messages:20}") int triggerMessages,
                                  @Value("${shana.memory.summary.batch-size:50}") int batchSize,
                                  @Value("${shana.llm.provider:OPENAI_COMPATIBLE}") LlmProvider provider) {
        if (triggerMessages <= 0) {
            throw new IllegalArgumentException("shana.memory.summary.trigger-messages 必须大于 0");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("shana.memory.summary.batch-size 必须大于 0");
        }
        this.archiveProvider = archiveProvider;
        this.longTermMemoryProvider = longTermMemoryProvider;
        this.llmClientRegistry = llmClientRegistry;
        this.triggerMessages = triggerMessages;
        this.batchSize = batchSize;
        this.provider = provider;
    }

    /**
     * 在后台尝试更新某个群的长期记忆。调用方无需等待，也不需要处理任何异常。
     *
     * <p>之所以把"要不要摘要"的判断也放进异步方法，而不是在请求线程里先判断：
     * 那需要在主链路上多查一次库，而这里本来就跑在后台，多查两次没有代价。
     */
    @Async
    public void summarizeAsync(String conversationId) {
        try {
            doSummarize(conversationId);
        } catch (Exception e) {
            // 只记一行可读信息，堆栈降到 debug：摘要失败是常态（模型抖动、超时、JSON 不合规），
            // 每次都在 warn 级别打满堆栈会把真正的问题淹掉
            log.warn("群聊 {} 的长期记忆更新失败，本次跳过: {}", conversationId, e.toString());
            log.debug("长期记忆更新失败详情: conversationId={}", conversationId, e);
        }
    }

    /**
     * 同步执行一次摘要尝试。
     *
     * @return 是否真的更新了记忆（没有新消息、不够触发阈值、被其它实例抢先都会返回 false）
     */
    boolean doSummarize(String conversationId) {
        ConversationArchive archive = archiveProvider.getIfAvailable();
        LongTermMemory longTermMemory = longTermMemoryProvider.getIfAvailable();
        if (archive == null || longTermMemory == null) {
            // Redis / 内存模式下没有完整历史，长期记忆不成立，安静跳过
            return false;
        }

        long latest = archive.latestMessageId(conversationId);
        if (latest <= 0) {
            return false;
        }

        MemorySnapshot snapshot = longTermMemory.load(conversationId, 0);
        long pending = latest - snapshot.coveredMessageId();
        if (pending < triggerMessages) {
            return false;
        }

        List<ArchiveMessage> batch = archive.after(conversationId, snapshot.coveredMessageId(), batchSize);
        if (batch.isEmpty()) {
            return false;
        }

        SummaryDraft draft = parser.parse(requestDraft(snapshot.summary(), batch));
        long newCovered = batch.get(batch.size() - 1).id();

        if (!longTermMemory.advanceSummary(conversationId, draft.summary(), newCovered, snapshot.coveredMessageId())) {
            // 另一个实例已经把游标推到更靠后的位置，本次基于旧摘要算出的结果必须丢弃
            log.info("群聊 {} 的摘要已被其它实例推进，丢弃本次结果", conversationId);
            return false;
        }

        longTermMemory.addFacts(conversationId, draft.facts(), newCovered);
        log.info("群聊 {} 的长期记忆已更新：覆盖到消息 {}，新增事实 {} 条",
                conversationId, newCovered, draft.facts().size());
        return true;
    }

    private String requestDraft(String previousSummary, List<ArchiveMessage> batch) {
        ChatResponse response = llmClientRegistry.get(provider)
                .chat(new ChatRequest(provider, null,
                        List.of(ChatMessage.system(SYSTEM_PROMPT),
                                ChatMessage.user(renderInput(previousSummary, batch))),
                        TEMPERATURE, MAX_TOKENS));
        return response.content();
    }

    private static String renderInput(String previousSummary, List<ArchiveMessage> batch) {
        StringBuilder text = new StringBuilder("【已有摘要】\n");
        text.append(previousSummary == null || previousSummary.isBlank() ? "（暂无）" : previousSummary.strip());
        text.append("\n\n【新增对话】\n");

        for (ArchiveMessage archived : batch) {
            ChatMessage message = archived.message();
            if (message.role() == ChatRole.SYSTEM) {
                // system 是自己塞的人设与记忆，不属于群聊内容，喂回去只会让模型自我复述
                continue;
            }
            text.append('[').append(archived.id()).append("] ")
                    .append(speakerOf(message)).append('：')
                    .append(message.content().strip()).append('\n');
        }
        return text.toString().strip();
    }

    private static String speakerOf(ChatMessage message) {
        if (message.role() == ChatRole.ASSISTANT) {
            return "Shana";
        }
        // 没有昵称，只能用外部 ID 代替；prompt 里已要求摘要不要直接引用它
        return message.senderId() == null || message.senderId().isBlank() ? "某成员" : message.senderId();
    }
}

package com.shana.report;

import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.ChatRole;
import com.shana.model.LlmClient;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 用模型把样本消息归纳成"本月话题"。
 *
 * <p>整个类只有一条铁律：<b>永远返回结果，永不抛异常</b>。
 * 报表的主体价值是那些可以逐条核对的统计数字，话题总结只是增味。
 * 模型超时、限流、返回空串、被内容策略拦下——这些都只让报表少一段文字，
 * 不能让它整体失败。失败原因会原样带到报表里，避免"没有总结"被误读成"没什么可总结的"。
 *
 * <p>另一个约束是<b>样本不等于全量</b>。一个月几万条消息既塞不进上下文也不经济，
 * 归纳话题靠的是覆盖面：取数时按时间均匀抽样，并在 prompt 里明确告知只有样本，
 * 否则模型容易用"这个月大家一直在讨论 X"这种绝对口气描述局部观察。
 */
public class NarrativeGenerator {

    private static final Logger log = LoggerFactory.getLogger(NarrativeGenerator.class);

    private static final String SYSTEM_PROMPT = """
            你负责归纳一个群聊在一个自然月里的主要话题。输入是该月的统计摘要和按时间均匀抽取的样本消息。

            要求：
            1. 输出 3~5 条要点，使用 Markdown 无序列表，每条以 "- " 开头，每条不超过 40 字。
            2. 只写样本里真实出现的内容。样本是抽样的，所以用"提到""讨论了"这类措辞，
               不要写"一直在""全部都在"这种全称判断。
            3. 按讨论热度排序，最热闹的话题放第一条。
            4. 不评价成员个人，不给建议，不写寒暄。成员用 u-xxx 标识，需要指代时写"有成员"。
            5. 如果样本信息不足以归纳出任何话题，只输出一行：- 本月样本中未发现明确的话题聚集。

            只输出列表本身，不要标题，不要解释，不要代码块围栏。
            """;

    /** 归纳话题允许一点灵活度，但不要天马行空 */
    private static final double TEMPERATURE = 0.3;

    /** 5 条要点，每条 40 字，400 token 有充足余量 */
    private static final int MAX_TOKENS = 400;

    /** 单条样本最多保留多少字。长文消息往往是转贴或文档，截断不损失话题信息 */
    private static final int SAMPLE_CHARS = 80;

    private static final DateTimeFormatter SAMPLE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final LlmClientRegistry llmClientRegistry;
    private final LlmProvider provider;
    private final int maxSamples;
    private final boolean enabled;

    public NarrativeGenerator(LlmClientRegistry llmClientRegistry,
                              LlmProvider provider,
                              int maxSamples,
                              boolean enabled) {
        this.llmClientRegistry = llmClientRegistry;
        this.provider = provider;
        this.maxSamples = maxSamples;
        this.enabled = enabled;
    }

    /**
     * @param activity 已算好的统计结果，样本从中取
     * @return 叙述文本与失败原因，两者至少有一个非空
     */
    public NarrativeResult generate(GroupActivity activity) {
        if (!enabled) {
            return NarrativeResult.failed("话题总结已关闭（shana.report.narrative.enabled=false）");
        }
        if (activity.samples().isEmpty()) {
            return NarrativeResult.failed("没有可用于归纳的样本消息");
        }

        List<SampleMessage> samples = activity.samples().stream().limit(maxSamples).toList();
        String prompt = buildPrompt(activity, samples);

        try {
            LlmClient client = llmClientRegistry.get(provider);
            ChatResponse response = client.chat(new ChatRequest(
                    provider,
                    null,
                    List.of(
                            new ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                            new ChatMessage(ChatRole.USER, prompt)),
                    TEMPERATURE,
                    MAX_TOKENS));

            String text = response.content() == null ? "" : response.content().strip();
            if (text.isEmpty()) {
                return NarrativeResult.failed("模型返回了空内容");
            }
            log.info("群 {} 的 {} 话题总结生成完成，样本 {} 条，token {}+{}",
                    activity.conversationId(), activity.period().label(),
                    samples.size(), response.usage().promptTokens(), response.usage().completionTokens());
            return NarrativeResult.ok(text);
        } catch (Exception e) {
            // 只记一行：总结失败是报表流程里的常态分支，不是需要人工介入的故障
            log.warn("群 {} 的 {} 话题总结生成失败: {}",
                    activity.conversationId(), activity.period().label(), e.toString());
            return NarrativeResult.failed("调用模型失败：" + e.getMessage());
        }
    }

    private String buildPrompt(GroupActivity activity, List<SampleMessage> samples) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("统计周期：").append(activity.period().displayName()).append('\n');
        sb.append("成员发言 ").append(activity.totalMessages()).append(" 条，活跃成员 ")
                .append(activity.activeMembers()).append(" 人。\n");
        sb.append("以下是按时间均匀抽取的 ").append(samples.size())
                .append(" 条样本（原始消息共 ").append(activity.totalMessages()).append(" 条）：\n\n");

        for (SampleMessage sample : samples) {
            sb.append('[').append(sample.createdAt().format(SAMPLE_TIME)).append("] ");
            if (sample.senderId() != null) {
                sb.append(sample.senderId()).append(": ");
            }
            String content = sample.content().replace('\n', ' ').strip();
            if (content.length() > SAMPLE_CHARS) {
                content = content.substring(0, SAMPLE_CHARS) + "…";
            }
            sb.append(content).append('\n');
        }
        return sb.toString();
    }

    /** 总结结果：成功了给文本，失败了给原因，二者互斥。 */
    public record NarrativeResult(String narrative, String error) {

        public static NarrativeResult ok(String narrative) {
            return new NarrativeResult(narrative, null);
        }

        public static NarrativeResult failed(String error) {
            return new NarrativeResult("", error);
        }
    }
}

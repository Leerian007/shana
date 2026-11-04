package com.shana.core.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.memory.FactKind;
import com.shana.memory.MemoryFact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把模型返回的文本解析成 {@link SummaryDraft}。
 *
 * <p>需要容错的地方比想象中多：
 * 模型会包一层 ```json 代码块、会在 JSON 前后加"好的，以下是结果："、
 * 会把 kind 写成小写、会多给几个字段。要求它"严格输出"是没用的，
 * 解析器必须自己能把这些剥掉，否则线上会频繁出现"摘要功能不工作"。
 *
 * <p>但容错是有边界的：<b>摘要正文为空一律判失败</b>。空摘要写进去会把之前
 * 累积的记忆一次性抹掉，这比本次不更新严重得多。
 *
 * <p>包级可见，只服务于 {@link ConversationSummarizer}，可直接单测。
 */
final class SummaryDraftParser {

    /** 摘要正文上限：来源 prompt 要求 300 字，这里留足余量只做兜底 */
    private static final int MAX_SUMMARY_CHARS = 600;

    /** 与 memory_fact.content 的列宽保持一致，超出会被数据库拒绝 */
    private static final int MAX_FACT_CONTENT_CHARS = 200;

    /** 与 memory_fact.subject 的列宽保持一致 */
    private static final int MAX_SUBJECT_CHARS = 64;

    /** 单轮最多采纳的事实条数，防止模型一口气吐出几十条把表灌满 */
    private static final int MAX_FACTS = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    SummaryDraft parse(String raw) {
        RawDraft draft;
        try {
            draft = MAPPER.readValue(extractJsonObject(raw), RawDraft.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("模型返回的记忆结果不是合法 JSON: " + abbreviate(raw), e);
        }

        String summary = draft.summary() == null ? "" : draft.summary().strip();
        if (summary.isEmpty()) {
            throw new IllegalStateException("模型返回的摘要为空，放弃本次更新");
        }

        return new SummaryDraft(truncate(summary, MAX_SUMMARY_CHARS), toFacts(draft.facts()));
    }

    /**
     * 从可能裹着解释文字或代码块的返回里抠出第一个 JSON 对象。
     *
     * <p>取"第一个 { 到最后一个 }"，而不是用正则匹配：摘要正文里本来就可能出现花括号，
     * 只有最外层的大括号范围是可靠的。
     */
    private static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("模型返回为空");
        }

        String text = stripCodeFence(raw.strip());
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型返回里找不到 JSON 对象: " + abbreviate(raw));
        }
        return text.substring(start, end + 1);
    }

    private static String stripCodeFence(String text) {
        int fenceStart = text.indexOf("```");
        if (fenceStart < 0) {
            return text;
        }
        int contentStart = text.indexOf('\n', fenceStart);
        if (contentStart < 0) {
            return text;
        }
        int fenceEnd = text.indexOf("```", contentStart);
        return fenceEnd < 0 ? text.substring(contentStart + 1) : text.substring(contentStart + 1, fenceEnd);
    }

    private static List<MemoryFact> toFacts(List<RawFact> rawFacts) {
        if (rawFacts == null || rawFacts.isEmpty()) {
            return List.of();
        }

        List<MemoryFact> facts = new ArrayList<>(rawFacts.size());
        for (RawFact raw : rawFacts) {
            if (facts.size() >= MAX_FACTS) {
                break;
            }
            String content = raw == null || raw.content() == null ? "" : raw.content().strip();
            if (content.isEmpty()) {
                // 没有内容的事实没有任何价值，直接丢弃，不要让它变成一条空记录
                continue;
            }
            facts.add(MemoryFact.of(
                    parseKind(raw.kind()),
                    truncate(raw.subject() == null ? "" : raw.subject().strip(), MAX_SUBJECT_CHARS),
                    truncate(content, MAX_FACT_CONTENT_CHARS)));
        }
        return List.copyOf(facts);
    }

    /** 未知类别降级成 FACT：宁可分类不准，也别丢掉这条信息。 */
    private static FactKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return FactKind.FACT;
        }
        try {
            return FactKind.valueOf(kind.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FactKind.FACT;
        }
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
    }

    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        String oneLine = raw.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawDraft(String summary, List<RawFact> facts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawFact(String kind, String subject, String content) {
    }
}

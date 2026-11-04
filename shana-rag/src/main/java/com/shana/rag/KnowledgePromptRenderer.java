package com.shana.rag;

import com.shana.rag.store.RetrievedChunk;

import java.util.List;

/**
 * 把检索结果渲染成一条 system 消息。
 *
 * <p>为什么单独成一条消息而不是拼进人设：资料每轮都变，人设不该跟着变；
 * 而且资料是"这一轮才需要"的上下文，用完即弃，混进人设后就没法整段丢弃了。
 *
 * <p>不用 JSON 或 Markdown 表格：模型对"编号 + 出处 + 正文"这种朴素结构最稳，
 * 复杂格式反而容易被当成需要解释的内容照抄一遍。
 */
public final class KnowledgePromptRenderer {

    /** 标题截断长度：标题通常只有几个字，出现在 prompt 里只是为了让模型能说出处。 */
    private static final int MAX_TITLE_CHARS = 60;

    private KnowledgePromptRenderer() {
    }

    public static String render(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(256);
        builder.append("【参考资料】以下是知识库中与当前问题相关的片段。")
                .append("请优先依据这些资料回答；资料没有覆盖的部分如实说明，不要编造。");

        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            builder.append("\n\n[").append(index++).append("]");
            String title = chunk.documentTitle();
            if (title != null && !title.isBlank()) {
                builder.append("《").append(truncate(title.strip())).append("》");
            }
            builder.append('\n').append(chunk.content() == null ? "" : chunk.content().strip());
        }
        return builder.toString();
    }

    private static String truncate(String title) {
        return title.length() <= MAX_TITLE_CHARS ? title : title.substring(0, MAX_TITLE_CHARS) + "…";
    }
}

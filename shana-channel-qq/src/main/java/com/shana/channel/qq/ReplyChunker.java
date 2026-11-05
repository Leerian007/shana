package com.shana.channel.qq;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一段长回复切成多条消息。
 *
 * <p>QQ 单条消息不是不能放下几千字，而是<b>没人愿意读一屏</b>，客户端也会出现滚动卡顿。
 * 切成多条还有个好处置：每条都能独立被引用、被回复。
 *
 * <p>切分优先贴合语义：先找换行，再找句号/问号/感叹号，实在没有才硬切。
 * 硬切必须按 <b>code point</b> 走，按 {@code char} 切会把 emoji 的代理对劈成两个孤立字符，
 * 发出去就是一串 {@code \uFFFD}。
 */
public final class ReplyChunker {

    /** 长度达到窗口 1/3 以上的边界才算"值得停"，否则会切出一堆四五个字的碎片。 */
    private static final double MIN_BOUNDARY_RATIO = 0.34;

    private static final List<String> SENTENCE_ENDS = List.of("。", "！", "？", "；", "!", "?", ";", "…");

    private final int maxChars;

    public ReplyChunker(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.maxChars = maxChars;
    }

    public int maxChars() {
        return maxChars;
    }

    public List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int remaining = text.codePointCount(0, text.length());

        while (remaining > maxChars) {
            int hardEnd = text.offsetByCodePoints(start, maxChars);
            int cut = findBoundary(text, start, hardEnd);
            if (cut <= start) {
                cut = hardEnd;
            }
            addIfNotBlank(chunks, text.substring(start, cut));
            start = skipWhitespace(text, cut);
            remaining = text.codePointCount(start, text.length());
        }
        if (start < text.length()) {
            addIfNotBlank(chunks, text.substring(start));
        }
        return chunks;
    }

    /**
     * 在 {@code [start, hardEnd)} 内找一个自然的断点。
     *
     * @return 断点位置（该位置之前的字符属于本段），找不到返回 {@code start}
     */
    private int findBoundary(String text, int start, int hardEnd) {
        int minLength = (int) ((hardEnd - start) * MIN_BOUNDARY_RATIO);
        int earliest = start + minLength;

        int newline = text.lastIndexOf('\n', hardEnd - 1);
        if (newline >= earliest) {
            return newline + 1;
        }
        int best = -1;
        for (String end : SENTENCE_ENDS) {
            int index = text.lastIndexOf(end, hardEnd - 1);
            if (index >= earliest) {
                best = Math.max(best, index + end.length());
            }
        }
        return best;
    }

    private static int skipWhitespace(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static void addIfNotBlank(List<String> chunks, String chunk) {
        String trimmed = chunk.strip();
        if (!trimmed.isEmpty()) {
            chunks.add(trimmed);
        }
    }
}

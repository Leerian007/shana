package com.shana.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把长文切成适合做向量检索的片段。
 *
 * <p>三条切分原则，按优先级：
 * <ol>
 *   <li><b>不切碎语义</b>：先按空行分段，再按换行分行，最后才按句末标点切。
 *       一个 chunk 里只有一个半句，向量本身就没有意义，检索出来模型也读不懂。</li>
 *   <li><b>块与块之间要有重叠</b>：答案常常横跨两段，没有重叠时"上半句在 A 块、下半句在 B 块"，
 *       两块单独看都不完整，一起召回又浪费配额。</li>
 *   <li><b>长度用字符数近似</b>：中文 1 字≈1 token，英文 1 token≈4 字符。
 *       精确 token 数需要引入分词器，为一个启发式参数引入依赖不划算；
 *       宁可把 {@code maxChars} 调保守一点。</li>
 * </ol>
 *
 * <p>无状态、线程安全。
 */
public final class TextSplitter {

    /** 段落边界：一个或多个空行。 */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");

    /** 句末标点，含中文全角与英文半角；零宽断言保证标点留在前一句末尾。 */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[。！？!?；;])");

    private static final Pattern EXCESS_NEWLINES = Pattern.compile("\\n{3,}");

    private final int maxChars;
    private final int overlapChars;
    private final int minChars;

    /**
     * @param maxChars     单块上限（字符数），建议 600~1000
     * @param overlapChars 相邻块的重叠字符数，建议为 maxChars 的 10%~20%
     * @param minChars     最小块长度，低于此值的尾块会并入前一块，避免产出"一句话 chunk"
     */
    public TextSplitter(int maxChars, int overlapChars, int minChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须大于 0，当前 " + maxChars);
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            // overlap >= maxChars 会让切分原地打转（每块都完整包含上一块）
            throw new IllegalArgumentException("overlapChars 必须落在 [0, maxChars) 区间，当前 " + overlapChars);
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
        this.minChars = Math.max(minChars, 0);
    }

    public List<String> split(String text) {
        if (text == null) {
            return List.of();
        }
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> units = toUnits(normalized);
        if (units.isEmpty()) {
            return List.of();
        }
        return mergeTinyTail(assemble(units));
    }

    /** 统一换行符与空白：CRLF 混用会让段落正则失效，多余空行则会切出大量空 chunk。 */
    private static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return EXCESS_NEWLINES.matcher(unified).replaceAll("\n\n").strip();
    }

    /**
     * 拆成"不可再分的最小单元"：句子级别，长度不超过 {@code maxChars}。
     * <p>换行符被保留在单元末尾，让 chunk 内部仍然看得出原来的结构（列表、段落）。
     */
    private List<String> toUnits(String text) {
        List<String> units = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_BREAK.split(text);

        for (int p = 0; p < paragraphs.length; p++) {
            String[] lines = paragraphs[p].split("\n");
            for (int l = 0; l < lines.length; l++) {
                String line = lines[l].strip();
                if (line.isEmpty()) {
                    continue;
                }
                // 行尾标记：段落之间留空行，行之间留单换行，拼接后可读性接近原文
                String suffix = l == lines.length - 1
                        ? (p == paragraphs.length - 1 ? "" : "\n\n")
                        : "\n";

                String[] sentences = SENTENCE_BREAK.split(line);
                for (int s = 0; s < sentences.length; s++) {
                    String sentence = sentences[s].strip();
                    if (sentence.isEmpty()) {
                        continue;
                    }
                    String withSuffix = s == sentences.length - 1 ? sentence + suffix : sentence;
                    addHardChunks(units, withSuffix);
                }
            }
        }
        return units;
    }

    /** 单句仍然超长（无标点的长串、代码、表格行）时按长度硬切。 */
    private void addHardChunks(List<String> units, String sentence) {
        if (sentence.length() <= maxChars) {
            units.add(sentence);
            return;
        }
        for (int from = 0; from < sentence.length(); from += maxChars) {
            int to = Math.min(from + maxChars, sentence.length());
            units.add(sentence.substring(from, to));
        }
    }

    /** 贪心累积到接近 {@code maxChars}，块间按 {@code overlapChars} 回退形成重叠。 */
    private List<String> assemble(List<String> units) {
        List<String> blocks = new ArrayList<>();
        int start = 0;

        while (start < units.size()) {
            int end = start;
            int length = 0;
            while (end < units.size() && length + units.get(end).length() <= maxChars) {
                length += units.get(end).length();
                end++;
            }
            if (end == start) {
                // 防御：单个单元超出上限（理论上 addHardChunks 已排除）
                end = start + 1;
            }

            String block = String.join("", units.subList(start, end)).strip();
            if (!block.isEmpty()) {
                blocks.add(block);
            }
            if (end >= units.size()) {
                break;
            }

            // 回退形成重叠；start > 原起点 保证每轮一定前进，不会死循环
            int next = end;
            int overlap = 0;
            while (next > start + 1 && overlap + units.get(next - 1).length() <= overlapChars) {
                overlap += units.get(next - 1).length();
                next--;
            }
            start = next;
        }
        return blocks;
    }

    /**
     * 尾块过短时并入前一块。
     *
     * <p>合并后这一块会略超 {@code maxChars}——这是有意的取舍：
     * 一个 10 字的 chunk 几乎检索不到任何东西，而超长 10% 对上下文预算没有实质影响。
     */
    private List<String> mergeTinyTail(List<String> blocks) {
        if (blocks.size() < 2) {
            return blocks;
        }
        int last = blocks.size() - 1;
        if (blocks.get(last).length() < minChars) {
            String merged = blocks.get(last - 1) + "\n" + blocks.get(last);
            blocks.set(last - 1, merged);
            blocks.remove(last);
        }
        return blocks;
    }
}

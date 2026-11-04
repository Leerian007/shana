package com.shana.memory;

import java.util.Objects;

/**
 * 一条被抽取出来的关键信息。
 *
 * <p>刻意不加置信度分数：模型给的概率往往是随口编的，按它过滤反而会误删正确条目。
 * 质量靠两点保证——抽取时的 prompt 约束，以及入库时的唯一约束去重。
 *
 * @param kind    类别
 * @param subject 主体，通常是成员名；没有明确主体时为空字符串（不用 null）
 * @param content 一句话事实，不含主语（主语在 {@code subject} 里）
 */
public record MemoryFact(FactKind kind, String subject, String content) {

    public MemoryFact {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public static MemoryFact of(FactKind kind, String subject, String content) {
        return new MemoryFact(kind, subject == null ? "" : subject.strip(), content.strip());
    }
}

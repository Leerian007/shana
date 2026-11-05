package com.shana.report;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 单个成员在一个周期内的发言统计。
 *
 * @param senderId     成员标识
 * @param messageCount 发言条数
 * @param charCount    发言总字数（字符数，不是 token）
 * @param firstAt      本周期内第一次发言时间
 * @param lastAt       本周期内最后一次发言时间
 */
public record MemberActivity(
        String senderId,
        int messageCount,
        long charCount,
        OffsetDateTime firstAt,
        OffsetDateTime lastAt) {

    public MemberActivity {
        Objects.requireNonNull(senderId, "senderId must not be null");
    }

    /** 平均每条多少个字。条数很少时这个值没有意义，调用方自己决定要不要展示。 */
    public int averageChars() {
        return messageCount == 0 ? 0 : (int) (charCount / messageCount);
    }
}

package com.shana.report;

/**
 * 某个小时的消息量。
 *
 * <p>小时按东八区本地时间切，而不是 UTC：报表要回答的是"这个群几点最热闹"，
 * 用 UTC 会把晚间高峰显示成下午。
 */
public record HourlyVolume(int hour, int count) {

    public HourlyVolume {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour 必须在 0~23 之间，实际为: " + hour);
        }
    }
}

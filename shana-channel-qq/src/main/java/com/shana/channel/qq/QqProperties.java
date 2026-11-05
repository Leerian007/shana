package com.shana.channel.qq;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * QQ 渠道配置。
 *
 * <p>取值全部在 {@code application.yml} 里给出默认值，本类只做承载，不放业务判断——
 * 判断集中在 {@link ReplyPolicy}，这样"回不回"的规则可以脱离 Spring 单测。
 *
 * <p>字段一律用包装类型：配置项缺失时得到 {@code null} 而不是 0/false，
 * 由 {@code xxxOrDefault()} 显式决定缺省语义。用基本类型的话，
 * "没配" 与 "配成 0" 长得一模一样，排查会很难受。
 *
 * @param wsUrl                 NapCat 正向 WebSocket 地址（在 NapCat 里建「WebSocket 服务器」）
 * @param accessToken           与 NapCat 侧一致的 token，留空表示不鉴权
 * @param apiTimeout            单次动作调用（发消息、查状态）的超时
 * @param heartbeatInterval     心跳间隔，{@link Duration#ZERO} 表示关闭
 * @param reconnectInitialDelay 断线重连的初始等待，之后按 2 的幂退避
 * @param reconnectMaxDelay     退避上限
 * @param debounceWindow        同一会话连续消息的聚合窗口
 * @param debounceMaxChars      聚合后的正文上限，超出部分丢弃（防止刷屏把 prompt 撑爆）
 * @param replyMaxChars         单条 QQ 消息的字符上限，超过则分段发送
 * @param replyInterval         分段之间的间隔，发太快容易被风控
 * @param groupEnabled          是否响应群聊
 * @param requireAt             群聊是否必须 @ 机器人才响应
 * @param groupWhitelist        群白名单，空表示所有群
 * @param privateEnabled        是否响应私聊
 * @param privateSubTypes       允许的私聊子类型：friend（好友）/ group（群临时会话）/ other（陌生人）
 * @param userWhitelist         用户白名单，空表示所有用户
 * @param executorCoreSize      问答线程池核心线程数
 * @param executorMaxSize       最大线程数
 * @param executorQueueCapacity 等待队列长度
 */
@ConfigurationProperties(prefix = "shana.qq")
public record QqProperties(
        String wsUrl,
        String accessToken,
        Duration apiTimeout,
        Duration heartbeatInterval,
        Duration reconnectInitialDelay,
        Duration reconnectMaxDelay,
        Duration debounceWindow,
        Integer debounceMaxChars,
        Integer replyMaxChars,
        Duration replyInterval,
        Boolean groupEnabled,
        Boolean requireAt,
        List<String> groupWhitelist,
        Boolean privateEnabled,
        List<String> privateSubTypes,
        List<String> userWhitelist,
        Integer executorCoreSize,
        Integer executorMaxSize,
        Integer executorQueueCapacity) {

    /** 没填地址时连本机默认端口：NapCat 的「WebSocket 服务器」默认就是 3001。 */
    public String wsUrlOrDefault() {
        return wsUrl == null || wsUrl.isBlank() ? "ws://127.0.0.1:3001" : wsUrl;
    }

    public Duration apiTimeoutOrDefault() {
        return apiTimeout == null || apiTimeout.isNegative() || apiTimeout.isZero() ? Duration.ofSeconds(10) : apiTimeout;
    }

    /** 默认 30 秒。NapCat 不会主动断开空闲连接，但中间的 NAT/代理会，靠心跳尽早发现。 */
    public Duration heartbeatIntervalOrDefault() {
        return heartbeatInterval == null ? Duration.ofSeconds(30) : heartbeatInterval;
    }

    public Duration reconnectInitialDelayOrDefault() {
        return reconnectInitialDelay == null || reconnectInitialDelay.isZero() ? Duration.ofSeconds(1) : reconnectInitialDelay;
    }

    public Duration reconnectMaxDelayOrDefault() {
        return reconnectMaxDelay == null || reconnectMaxDelay.isZero() ? Duration.ofSeconds(30) : reconnectMaxDelay;
    }

    /** 默认 900ms：足够把用户"一句话拆三条发"粘回来，又不至于让回复显得迟钝。 */
    public Duration debounceWindowOrDefault() {
        return debounceWindow == null ? Duration.ofMillis(900) : debounceWindow;
    }

    public int debounceMaxCharsOrDefault() {
        return debounceMaxChars == null || debounceMaxChars <= 0 ? 2000 : debounceMaxChars;
    }

    /** 默认 1200 字：QQ 单条消息上限远大于此，但一口气刷满屏没人看得下去。 */
    public int replyMaxCharsOrDefault() {
        return replyMaxChars == null || replyMaxChars <= 0 ? 1200 : replyMaxChars;
    }

    public Duration replyIntervalOrDefault() {
        return replyInterval == null ? Duration.ofMillis(400) : replyInterval;
    }

    public boolean groupEnabledOrDefault() {
        return groupEnabled == null || groupEnabled;
    }

    public boolean requireAtOrDefault() {
        return requireAt == null || requireAt;
    }

    public List<String> groupWhitelistOrEmpty() {
        return groupWhitelist == null ? List.of() : List.copyOf(groupWhitelist);
    }

    public boolean privateEnabledOrDefault() {
        return privateEnabled == null || privateEnabled;
    }

    /** 默认只认好友私聊：群临时会话与陌生人是骚扰和风控的主要来源。 */
    public List<String> privateSubTypesOrDefault() {
        return privateSubTypes == null || privateSubTypes.isEmpty() ? List.of("friend") : List.copyOf(privateSubTypes);
    }

    public List<String> userWhitelistOrEmpty() {
        return userWhitelist == null ? List.of() : List.copyOf(userWhitelist);
    }

    public int executorCoreSizeOrDefault() {
        return executorCoreSize == null || executorCoreSize <= 0 ? 2 : executorCoreSize;
    }

    public int executorMaxSizeOrDefault() {
        int max = executorMaxSize == null || executorMaxSize <= 0 ? 8 : executorMaxSize;
        return Math.max(max, executorCoreSizeOrDefault());
    }

    public int executorQueueCapacityOrDefault() {
        return executorQueueCapacity == null || executorQueueCapacity < 0 ? 200 : executorQueueCapacity;
    }
}

package com.shana.channel.qq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 以「正向 WebSocket」方式连接 NapCat 的 OneBot 实现。
 *
 * <p>方向说明：<b>我们主动连 NapCat</b>（NapCat 里建「WebSocket 服务器」）。
 * 反过来（NapCat 连我们）叫反向 WS，需要在本服务上开一个 WS 端点；那种方式适合
 * NapCat 在外网、后端在内网无法主动出网的场景。当前实现选择正向，因为它不需要
 * 在本服务上额外暴露端口，本机联调也最短。
 *
 * <p>为什么用 JDK 自带的 {@link HttpClient} 而不是 Spring 的 WebSocket 客户端：
 * 后者要引入 {@code spring-boot-starter-websocket}，而这里只需要"收 JSON 帧、发 JSON 帧"。
 * 更关键的是 JDK 的实现直接给到 {@code onText} 增量回调，不会整段缓冲，
 * 对事件推送这种高频小包是合适的。
 *
 * <p>三个容易被忽略的细节都在这儿处理了：
 * <ul>
 *   <li>一条消息可能被拆成多次 {@code onText} 回调（{@code last=false}），必须自己拼接，
 *       否则长消息会被截断成半句；</li>
 *   <li>请求 {@code echo} 与响应必须对上号，否则并发发送时会张冠李戴；</li>
 *   <li>断线必须退避重连，且不能叠加多个重连任务——弱网下会瞬间打出一堆连接。</li>
 * </ul>
 */
public final class OneBotWsClient implements OneBotApi, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OneBotWsClient.class);

    /** 退避上限的指数位：2^10 之后不再翻倍，避免算出天文数字的等待时间。 */
    private static final int MAX_BACKOFF_EXPONENT = 10;

    private final URI uri;
    private final String accessToken;
    private final Duration apiTimeout;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final Duration heartbeatInterval;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingCalls = new ConcurrentHashMap<>();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    private volatile WebSocket socket;
    private volatile boolean running;
    private volatile int failures;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile Consumer<JsonNode> eventListener = node -> { };

    public OneBotWsClient(String wsUrl, String accessToken, Duration apiTimeout,
                          Duration initialDelay, Duration maxDelay, Duration heartbeatInterval,
                          ObjectMapper objectMapper) {
        this.uri = URI.create(wsUrl);
        this.accessToken = accessToken;
        this.apiTimeout = apiTimeout;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.heartbeatInterval = heartbeatInterval;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(apiTimeout)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shana-qq-ws");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 注册事件监听。同一时刻只允许一个监听者：多渠道并存时应当各自持有自己的客户端实例。 */
    public void subscribe(Consumer<JsonNode> listener) {
        this.eventListener = listener == null ? node -> { } : listener;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        connect();
        if (heartbeatInterval != null && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative()) {
            long interval = heartbeatInterval.toMillis();
            heartbeatTask = scheduler.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public CompletableFuture<JsonNode> call(String action, Object params) {
        WebSocket current = socket;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NapCat 未连接，动作 " + action + " 未发送"));
        }

        String echo = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", action);
        payload.put("echo", echo);
        payload.set("params", params == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(params));

        String frame;
        try {
            frame = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingCalls.put(echo, future);
        current.sendText(frame, true).whenComplete((sent, error) -> {
            if (error != null) {
                pendingCalls.remove(echo);
                future.completeExceptionally(error);
            }
        });
        return future
                .orTimeout(apiTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> pendingCalls.remove(echo));
    }

    @Override
    public void close() {
        running = false;
        ScheduledFuture<?> heartbeat = heartbeatTask;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            // abort 而不是 sendClose：停机时不该等对方回应，也不该在 onClose 里再触发重连
            current.abort();
        }
        pendingCalls.values().forEach(future ->
                future.completeExceptionally(new IllegalStateException("QQ 渠道已关闭")));
        pendingCalls.clear();
        scheduler.shutdownNow();
    }

    private void connect() {
        if (!running || socket != null) {
            return;
        }
        WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(apiTimeout);
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        builder.buildAsync(uri, new FrameListener()).whenComplete((webSocket, error) -> {
            if (error != null) {
                log.warn("连接 NapCat 失败：{}", rootMessage(error));
                scheduleReconnect();
                return;
            }
            socket = webSocket;
            failures = 0;
            log.info("已连接 NapCat：{}", uri);
        });
    }

    private void scheduleReconnect() {
        if (!running || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = backoffMillis();
        log.info("{} ms 后重连 NapCat（第 {} 次）", delay, failures);
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private long backoffMillis() {
        int attempt = Math.min(failures, MAX_BACKOFF_EXPONENT);
        long delay = initialDelay.toMillis() * (1L << attempt);
        failures = Math.min(failures + 1, MAX_BACKOFF_EXPONENT);
        return Math.min(delay, maxDelay.toMillis());
    }

    private void heartbeat() {
        if (!running) {
            return;
        }
        if (socket == null) {
            // 心跳同时也是"断线后是否该重连"的兜底：万一 onClose 没被回调，这里能补上
            connect();
            return;
        }
        call("get_status", null).whenComplete((response, error) -> {
            if (error != null) {
                log.warn("NapCat 心跳失败，主动断开重连：{}", rootMessage(error));
                dropConnection();
            }
        });
    }

    private void dropConnection() {
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.abort();
        }
        scheduleReconnect();
    }

    private void dispatch(String frame) {
        JsonNode node;
        try {
            node = objectMapper.readTree(frame);
        } catch (JsonProcessingException e) {
            log.warn("无法解析 NapCat 下发帧：{}", abbreviate(frame));
            return;
        }
        if (node.hasNonNull("echo")) {
            completeCall(node);
            return;
        }
        if (!node.has("post_type")) {
            log.debug("忽略未知帧：{}", abbreviate(frame));
            return;
        }
        try {
            eventListener.accept(node);
        } catch (RuntimeException e) {
            // 业务侧抛错（解析失败、策略异常）不该掀翻整条连接
            log.warn("处理事件失败：{}", abbreviate(frame), e);
        }
    }

    private void completeCall(JsonNode response) {
        String echo = response.path("echo").asText();
        CompletableFuture<JsonNode> future = pendingCalls.remove(echo);
        if (future == null) {
            // 已超时的调用，响应晚到属于正常现象
            return;
        }
        int retcode = response.path("retcode").asInt(0);
        if (retcode != 0) {
            String message = response.path("wording").asText(response.path("msg").asText(""));
            future.completeExceptionally(new IllegalStateException(
                    "OneBot 调用失败 retcode=" + retcode + (message.isBlank() ? "" : " " + message)));
            return;
        }
        future.complete(response);
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName() : message;
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    /** 每个连接一个实例：拼接缓冲不能跨连接复用，否则重连时的残帧会混进新消息。 */
    private final class FrameListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                dispatch(frame);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("NapCat 连接关闭 code={} reason={}", statusCode, reason);
            if (socket == webSocket) {
                socket = null;
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("NapCat 连接异常：{}", rootMessage(error));
            if (socket == webSocket) {
                socket = null;
            }
            scheduleReconnect();
        }
    }
}

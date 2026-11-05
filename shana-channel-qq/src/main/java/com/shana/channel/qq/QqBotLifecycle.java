package com.shana.channel.qq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 把 QQ 渠道挂到应用生命周期上。
 *
 * <p>用 {@link SmartLifecycle} 而不是 {@code @PostConstruct}：连接是 IO 动作，
 * 放进 Bean 初始化里会让启动过程被网络阻塞；而且停机时要主动 abort 连接，
 * 否则 Tomcat 已经停了、WS 线程还在重连，日志里会留下一串无意义的报错。
 *
 * <p>启动阶段<b>不检查连通性</b>：NapCat 可能比后端晚几秒起来，也可能暂时不在线。
 * 连不上就按退避重试，让应用照常提供 HTTP 服务——渠道断了不该拖垮整个服务。
 */
public final class QqBotLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QqBotLifecycle.class);

    private final OneBotWsClient client;
    private final QqChatHandler handler;

    private volatile boolean running;

    public QqBotLifecycle(OneBotWsClient client, QqChatHandler handler) {
        this.client = client;
        this.handler = handler;
        // 订阅放在构造里，避免 start 之后再注册导致最早几条事件被丢掉
        this.client.subscribe(handler::onEvent);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        client.start();
        log.info("QQ 渠道已启动");
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        client.close();
        log.info("QQ 渠道已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

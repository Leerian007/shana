package com.shana.application;

import com.shana.channel.qq.QqBotLifecycle;
import com.shana.channel.qq.QqChatHandler;
import com.shana.channel.qq.QqMessageSender;
import com.shana.channel.qq.ReplyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QQ 渠道装配验证。
 *
 * <p>为什么需要它：{@code QqChannelConfiguration} 平时被 {@code shana.qq.enabled=false} 挡住，
 * 其余集成测试根本不会实例化这些 Bean。装配错误（依赖缺失、线程池注入歧义、配置绑定失败）
 * 会一直藏到"有人在生产上打开开关"的那一刻。
 *
 * <p>地址给一个必定连不上的端口：连接失败是异步的、可恢复的，正是要验证的行为——
 * <b>NapCat 不在线时应用照常启动，不能因此起不来</b>。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "shana.qq.enabled=true",
        "shana.qq.ws-url=ws://127.0.0.1:1",
        "shana.qq.heartbeat-interval=0s"
})
class QqChannelWiringTest {

    @Autowired
    private QqBotLifecycle lifecycle;

    @Autowired
    private QqChatHandler handler;

    @Autowired
    private QqMessageSender sender;

    @Autowired
    private ReplyPolicy policy;

    @Test
    @DisplayName("打开开关后渠道完成装配并进入运行状态")
    void shouldWireChannelWhenEnabled() {
        assertThat(handler).isNotNull();
        assertThat(sender).isNotNull();
        assertThat(policy).isNotNull();
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    @DisplayName("NapCat 连不上时既有消息不会被当成可回复的输入")
    void shouldStillApplyPolicyWithoutConnection() {
        assertThat(policy.decide(new com.shana.channel.qq.IncomingMessage(
                "1", 10000L, 20001L, 30001L, "normal", "没有 @ 的消息", false, null)).allowed())
                .isFalse();
    }
}

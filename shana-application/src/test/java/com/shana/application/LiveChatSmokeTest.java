package com.shana.application;

import com.shana.core.chat.ChatReply;
import com.shana.core.chat.ChatService;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import com.shana.model.adapter.OpenAiCompatibleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实模型冒烟测试：本类**会发起真实调用、消耗真实 token**，因此默认不执行。
 *
 * <p>两道闸门，缺一不可：
 * <ol>
 *   <li>环境变量 {@code SHANA_RUN_LIVE_TEST} 已设置（显式表达"我要花这笔钱"）；</li>
 *   <li>确实配置了 api-key。</li>
 * </ol>
 * 日常生活 {@code mvn test} 只会看到一条 skipped，不会误调用付费接口。
 *
 * <p>为什么单独一个类：单元测试和集成测试必须可重复、离线、零成本；
 * 真实调用不稳定、要花钱、依赖账号余额，三层测试的边界要分清楚。
 */
@ActiveProfiles("test")
@SpringBootTest
class LiveChatSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveChatSmokeTest.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private LlmClientRegistry llmClientRegistry;

    @Autowired
    private OpenAiCompatibleProperties properties;

    @BeforeEach
    void requireOptIn() {
//        assumeTrue(System.getenv("SHANA_RUN_LIVE_TEST") != null,
//                "未设置 SHANA_RUN_LIVE_TEST，跳过真实模型调用（会消耗 token）");
        assumeTrue(properties.apiKey() != null && !properties.apiKey().isBlank(),
                "未配置 api-key，跳过真实模型调用");
    }

    @Test
    @DisplayName("真实调用：两轮对话能记住上一轮内容")
    void shouldRememberPreviousTurn() {
        // 条件装配生效的直接证据：有 key 时容器里一定有这个适配器
        assertThat(llmClientRegistry.isAvailable(LlmProvider.OPENAI_COMPATIBLE))
                .as("已配置 api-key，适配器应当已装配")
                .isTrue();

        // 用随机会话 ID，避免历史数据干扰；也能验证上下文按会话隔离
        String conversationId = "smoke-" + UUID.randomUUID();

        ChatReply first = chatService.reply(conversationId, "记住这个词：紫罗兰。只回复“收到”。");
        log.info("第一轮 → content={} | promptTokens={} completionTokens={}",
                (Object) first.content(), (Object) first.usage().promptTokens(), (Object) first.usage().completionTokens());

        ChatReply second = chatService.reply(conversationId, "我刚才让你记住的词是什么？只回答那个词。");
        log.info("第二轮 → content={} | promptTokens={} completionTokens={}",
                (Object) second.content(), (Object) second.usage().promptTokens(), (Object) second.usage().completionTokens());

        assertThat(first.content()).isNotBlank();
        assertThat(first.usage().promptTokens()).isPositive();
        assertThat(second.content()).contains("紫罗兰");
        // 第二轮 prompt 必然比第一轮长（带上了第一轮问答），这是上下文真的被带上的硬证据
        assertThat(second.usage().promptTokens())
                .as("第二轮 prompt 应当包含第一轮历史")
                .isGreaterThan(first.usage().promptTokens());
    }
}

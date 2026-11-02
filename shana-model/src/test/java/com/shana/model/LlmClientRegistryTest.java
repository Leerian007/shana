package com.shana.model;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientRegistryTest {

    @Test
    @DisplayName("按 provider 路由到对应适配器")
    void shouldRouteByProvider() {
        LlmClient ollama = new StubLlmClient(LlmProvider.OLLAMA);
        LlmClientRegistry registry = new LlmClientRegistry(List.of(ollama));

        assertThat(registry.get(LlmProvider.OLLAMA)).isSameAs(ollama);
        assertThat(registry.isAvailable(LlmProvider.OLLAMA)).isTrue();
        assertThat(registry.availableProviders()).containsExactly(LlmProvider.OLLAMA);
    }

    @Test
    @DisplayName("没有对应适配器时抛出服务不可用异常")
    void shouldThrowWhenProviderMissing() {
        LlmClientRegistry registry = new LlmClientRegistry(List.of());

        assertThatThrownBy(() -> registry.get(LlmProvider.OPENAI_COMPATIBLE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OPENAI_COMPATIBLE")
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("同一个 provider 重复注册时启动期直接失败")
    void shouldRejectDuplicateProvider() {
        LlmClient first = new StubLlmClient(LlmProvider.CUSTOM);
        LlmClient second = new StubLlmClient(LlmProvider.CUSTOM);

        assertThatThrownBy(() -> new LlmClientRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CUSTOM");
    }

    /** 测试替身：不访问网络，只返回固定内容。 */
    private record StubLlmClient(LlmProvider provider) implements LlmClient {

        @Override
        public ChatResponse chat(ChatRequest request) {
            return new ChatResponse("stub", provider, request.model(), TokenUsage.unknown());
        }
    }
}

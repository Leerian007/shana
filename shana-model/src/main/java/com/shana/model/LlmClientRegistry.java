package com.shana.model;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按 {@link LlmProvider} 路由到具体的 {@link LlmClient} 实现。
 * <p>装配过程由 Spring 完成：容器里所有 {@link LlmClient} Bean 会被自动收集。
 * 当前阶段还没有任何适配器实现，因此 {@link #get(LlmProvider)} 会抛出"服务暂不可用"，
 * 后续新增适配器无需改动本类。
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    private final Map<LlmProvider, LlmClient> clients;

    public LlmClientRegistry(List<LlmClient> clients) {
        Map<LlmProvider, LlmClient> registered = new EnumMap<>(LlmProvider.class);
        for (LlmClient client : clients) {
            LlmClient existing = registered.putIfAbsent(client.provider(), client);
            if (existing != null) {
                // 同一个 provider 出现两个实现属于装配错误，启动期直接失败，避免运行期行为不确定
                throw new IllegalStateException("模型适配器重复注册: " + client.provider()
                        + " -> " + existing.getClass().getName() + ", " + client.getClass().getName());
            }
        }
        this.clients = Map.copyOf(registered);
        log.info("模型适配器注册完成，当前可用: {}", registered.keySet());
    }

    /**
     * 获取指定提供方的适配器。
     *
     * @throws BusinessException 没有对应实现时抛出，错误码为 {@link ErrorCode#SERVICE_UNAVAILABLE}
     */
    public LlmClient get(LlmProvider provider) {
        LlmClient client = clients.get(provider);
        if (client == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "没有可用的模型适配器: " + provider);
        }
        return client;
    }

    public boolean isAvailable(LlmProvider provider) {
        return clients.containsKey(provider);
    }

    public Set<LlmProvider> availableProviders() {
        return clients.keySet();
    }
}

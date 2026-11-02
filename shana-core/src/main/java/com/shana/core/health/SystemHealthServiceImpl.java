package com.shana.core.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 基于 JVM 运行时信息实现的健康状态查询，不依赖数据库、Redis 或模型服务。
 */
@Service
public class SystemHealthServiceImpl implements SystemHealthService {

    private final String applicationName;
    private final String applicationVersion;
    private final Environment environment;

    public SystemHealthServiceImpl(
            @Value("${spring.application.name:shana}") String applicationName,
            @Value("${shana.version:unknown}") String applicationVersion,
            Environment environment) {
        this.applicationName = applicationName;
        this.applicationVersion = applicationVersion;
        this.environment = environment;
    }

    @Override
    public SystemStatus currentStatus() {
        return new SystemStatus(
                "UP",
                applicationName,
                applicationVersion,
                effectiveProfiles(),
                Runtime.version().toString(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    }

    /**
     * 显式激活的 profile 优先；没有显式激活时返回默认 profile，
     * 否则本地默认 profile 场景下会显示为空列表，容易误解。
     */
    private List<String> effectiveProfiles() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0
                ? List.of(activeProfiles)
                : List.of(environment.getDefaultProfiles());
    }
}

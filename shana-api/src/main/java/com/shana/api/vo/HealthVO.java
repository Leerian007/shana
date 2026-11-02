package com.shana.api.vo;

import com.shana.core.health.SystemStatus;

import java.util.List;

/**
 * 健康检查响应体。
 * <p>领域模型 {@link SystemStatus} 不直接暴露给前端：接口字段可以独立演进。
 */
public record HealthVO(
        String status,
        String application,
        String version,
        List<String> activeProfiles,
        String javaVersion,
        long uptimeSeconds) {

    public static HealthVO from(SystemStatus status) {
        return new HealthVO(
                status.status(),
                status.application(),
                status.version(),
                status.activeProfiles(),
                status.javaVersion(),
                status.uptimeSeconds());
    }
}

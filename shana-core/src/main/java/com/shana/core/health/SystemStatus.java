package com.shana.core.health;

import java.util.List;

/**
 * 应用运行状态快照。
 *
 * @param status         运行状态，当前恒为 {@code UP}
 * @param application    应用名，来自 {@code spring.application.name}
 * @param version        应用版本，来自 Maven 构建版本
 * @param activeProfiles 生效的 Spring Profile
 * @param javaVersion    运行时 Java 版本
 * @param uptimeSeconds  已运行秒数
 */
public record SystemStatus(
        String status,
        String application,
        String version,
        List<String> activeProfiles,
        String javaVersion,
        long uptimeSeconds) {
}

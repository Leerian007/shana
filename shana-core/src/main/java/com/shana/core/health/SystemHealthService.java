package com.shana.core.health;

/**
 * 应用健康状态查询。
 * <p>Controller 只做协议转换，状态信息由本服务提供。
 */
public interface SystemHealthService {

    SystemStatus currentStatus();
}

package com.shana.api.controller;

import com.shana.api.vo.HealthVO;
import com.shana.common.api.Result;
import com.shana.core.health.SystemHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用健康检查接口。
 * <p>Controller 只做"调用服务 + 转换响应"，不写业务逻辑。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final SystemHealthService systemHealthService;

    @GetMapping("/health")
    public Result<HealthVO> health() {
        return Result.success(HealthVO.from(systemHealthService.currentStatus()));
    }
}

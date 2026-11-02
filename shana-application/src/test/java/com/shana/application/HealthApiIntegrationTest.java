package com.shana.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试：验证应用能完整启动（含所有模块装配）并返回统一响应结构。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class HealthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/v1/health 返回统一响应结构")
    void shouldReturnUnifiedHealthResponse() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.application").value("shana"))
                // 只断言"有 profile 信息"，不锁死具体值：测试跑在 test profile 下，
                // 线上跑在 prod 下，写死 dev 会让这条断言随环境变化而失效
                .andExpect(jsonPath("$.data.activeProfiles").isNotEmpty());
    }

    @Test
    @DisplayName("访问不存在的路径返回统一格式的 404")
    void shouldReturnUnifiedNotFoundForUnknownPath() throws Exception {
        mockMvc.perform(get("/api/v1/not-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("Actuator 健康检查可用")
    void shouldExposeActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}

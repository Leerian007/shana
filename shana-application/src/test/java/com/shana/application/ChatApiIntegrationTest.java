package com.shana.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答接口的"无模型"场景：整条链路真实装配，但没有配置 api-key，
 * 因此容器里没有 LlmClient，调用会一路走到 LlmClientRegistry 抛出服务不可用。
 *
 * <p>这个测试同时证明了两件事：条件装配不会阻止应用启动；业务异常能被统一响应结构翻译。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "shana.llm.openai-compatible.api-key=")
@AutoConfigureMockMvc
class ChatApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("没有可用模型适配器时返回 503 统一结构")
    void shouldReturnServiceUnavailableWithoutAdapter() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"group-1","message":"你好"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message", containsString("没有可用的模型适配器")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("message 为空时返回 400 且提示字段名")
    void shouldRejectBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"group-1","message":"  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("message")));
    }
}

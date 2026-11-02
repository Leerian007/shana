package com.shana.application;

import com.shana.core.chat.ChatReply;
import com.shana.core.chat.ChatService;
import com.shana.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答接口的成功路径：把 {@link ChatService} 换成 Mockito 替身，
 * 只验证 HTTP 参数绑定与响应结构，真实编排逻辑由 core 模块的单测覆盖。
 *
 * <p>集成测试里绝不调用真实模型：慢、要花钱、结果还不稳定。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ChatApiSuccessTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    @DisplayName("POST /api/v1/chat 返回统一结构与 token 用量")
    void shouldReturnUnifiedChatResponse() throws Exception {
        given(chatService.reply("group-1", "你好"))
                .willReturn(new ChatReply("group-1", "我是 Shana", new TokenUsage(5, 9)));

        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"group-1","message":"你好"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.conversationId").value("group-1"))
                .andExpect(jsonPath("$.data.content").value("我是 Shana"))
                .andExpect(jsonPath("$.data.promptTokens").value(5))
                .andExpect(jsonPath("$.data.completionTokens").value(9));
    }

    @Test
    @DisplayName("缺少 conversationId 时返回 400")
    void shouldRejectMissingConversationId() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"你好"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}

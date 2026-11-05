package com.shana.application;

import com.shana.common.exception.BusinessException;
import com.shana.common.api.ErrorCode;
import com.shana.core.chat.ChatReply;
import com.shana.core.chat.ChatService;
import com.shana.core.chat.ChatStreamHandler;
import com.shana.model.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式问答接口的 HTTP 契约测试。
 *
 * <p>{@link ChatService} 换成替身，只验证"事件格式、错误路径、连接方式"这三件属于传输层的事；
 * 生成逻辑本身由 core 与 agent 的单测覆盖。这里绝不调用真实模型。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    @DisplayName("POST /api/v1/chat/stream 以 SSE 逐段推送，并以 done 收尾")
    void shouldStreamEvents() throws Exception {
        willAnswer(invocation -> {
            ChatStreamHandler handler = invocation.getArgument(3);
            handler.onRoundStart(1);
            handler.onDelta("你");
            handler.onDelta("好");
            handler.onComplete(new ChatReply("group-1", "你好", new TokenUsage(3, 2), List.of()));
            return null;
        }).given(chatService).replyStream(eq("group-1"), nullable(String.class), eq("你好"), any());

        MvcResult result = mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"conversationId":"group-1","senderId":"u-1001","message":"你好"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        // 按 UTF-8 解码字节流：MockHttpServletResponse 默认用 ISO-8859-1 转字符串，
        // 而 SSE 线上字节确实是 UTF-8，断言不该被这个默认值影响
        String body = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                // 会话号先到，客户端才能确认"连上了、是哪个会话"
                .contains("event:meta")
                .contains("{\"conversationId\":\"group-1\"}")
                .contains("event:delta")
                .contains("{\"text\":\"你\"}")
                .contains("{\"text\":\"好\"}")
                // done 里带的是落库正文与用量，客户端应以它为准
                .contains("event:done")
                .contains("\"content\":\"你好\"")
                .contains("\"completionTokens\":2")
                .doesNotContain("event:error");
    }

    @Test
    @DisplayName("第二轮开始发 reset，让客户端丢掉过渡语")
    void shouldSendResetOnSecondRound() throws Exception {
        willAnswer(invocation -> {
            ChatStreamHandler handler = invocation.getArgument(3);
            handler.onRoundStart(1);
            handler.onDelta("我查一下");
            handler.onTool("group_message_stats");
            handler.onRoundStart(2);
            handler.onDelta("张三");
            handler.onComplete(new ChatReply("group-1", "张三", TokenUsage.unknown(),
                    List.of("group_message_stats")));
            return null;
        }).given(chatService).replyStream(anyString(), nullable(String.class), anyString(), any());

        MvcResult result = mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"group-1","message":"谁最活跃"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();

        String body = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("event:tool")
                .contains("{\"name\":\"group_message_stats\"}")
                .contains("event:reset")
                .contains("{\"round\":2}");
    }

    @Test
    @DisplayName("生成中途失败：以 error 事件收尾，HTTP 仍是 200")
    void shouldSendErrorEvent() throws Exception {
        willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "模型服务返回错误状态 401"))
                .given(chatService).replyStream(anyString(), nullable(String.class), anyString(), any());

        MvcResult result = mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"group-1","message":"你好"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();

        String body = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("event:meta")
                .contains("event:error")
                .contains("\"code\":503")
                .contains("模型服务返回错误状态 401")
                // 失败时不能同时给出 done，否则客户端无法判断该显示答案还是错误
                .doesNotContain("event:done");
    }

    @Test
    @DisplayName("参数校验失败发生在推事件之前，仍然返回统一 JSON 结构")
    void shouldRejectInvalidRequestBeforeStreaming() throws Exception {
        mockMvc.perform(post("/api/v1/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"group-1","message":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("message")));
    }
}

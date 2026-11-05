package com.shana.infrastructure.agent;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.agent.tool.ToolContext;
import com.shana.infrastructure.persistence.entity.ChatMessageEntity;
import com.shana.infrastructure.persistence.mapper.ChatMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 群聊统计工具的测试：只验证"模型给了什么参数、我们回什么文本"，
 * 真正的 SQL 由 MyBatis-Plus 生成，属于联调范畴。
 */
class GroupMessageStatsToolTest {

    private static final ToolContext CONTEXT = new ToolContext("group-1", "u-1001");

    private final ChatMessageMapper mapper = mock(ChatMessageMapper.class);
    private final GroupMessageStatsTool tool = new GroupMessageStatsTool(mapper, new ObjectMapper());

    @Test
    @DisplayName("按发言条数降序输出，带上合计与群 ID")
    void shouldRenderRanking() {
        given(mapper.selectMaps(any())).willReturn(List.of(
                Map.<String, Object>of("sender_id", "u-1001", "message_count", 12L),
                Map.<String, Object>of("sender_id", "u-1002", "message_count", 5L)));

        String result = tool.execute(CONTEXT, "{\"days\":7}");

        assertThat(result)
                .contains("group-1")
                .contains("最近 7 天")
                .contains("1. u-1001：12 条")
                .contains("2. u-1002：5 条")
                .contains("合计 17 条，涉及 2 位成员");
    }

    @Test
    @DisplayName("没有数据时说明原因，而不是返回空字符串")
    void shouldExplainEmptyResult() {
        given(mapper.selectMaps(any())).willReturn(List.of());

        String result = tool.execute(CONTEXT, "{}");

        assertThat(result).contains("没有采集到带发送者标识的消息");
    }

    @Test
    @DisplayName("天数越界时夹到 1~90，而不是把异常抛给模型")
    void shouldClampDays() {
        given(mapper.selectMaps(any())).willReturn(List.of());

        assertThat(tool.execute(CONTEXT, "{\"days\":1000}")).contains("最近 90 天");
        assertThat(tool.execute(CONTEXT, "{\"days\":0}")).contains("最近 1 天");
    }

    @Test
    @DisplayName("参数不是 JSON 时回落到默认 7 天")
    void shouldFallBackOnBadArguments() {
        given(mapper.selectMaps(any())).willReturn(List.of());

        assertThat(tool.execute(CONTEXT, "not-json")).contains("最近 7 天");
        assertThat(tool.execute(CONTEXT, null)).contains("最近 7 天");
    }

    @Test
    @DisplayName("会话 ID 来自上下文而不是模型参数：模型伪造的值进不了 SQL")
    @SuppressWarnings("unchecked")
    void shouldScopeQueryToContextConversation() {
        given(mapper.selectMaps(any())).willReturn(List.of());

        tool.execute(new ToolContext("group-42", "u-2002"), "{\"conversationId\":\"group-1\"}");

        ArgumentCaptor<Wrapper<ChatMessageEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectMaps(captor.capture());

        Wrapper<ChatMessageEntity> wrapper = captor.getValue();
        assertThat(wrapper.getCustomSqlSegment()).contains("conversation_id");
        // 参数值单独存放，模型写进 arguments 的 conversationId 根本不会被读
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        assertThat(params.values())
                .contains("group-42")
                .doesNotContain("group-1");
    }
}

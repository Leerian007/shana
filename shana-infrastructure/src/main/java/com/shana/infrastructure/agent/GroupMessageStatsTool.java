package com.shana.infrastructure.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.agent.tool.Tool;
import com.shana.agent.tool.ToolContext;
import com.shana.infrastructure.persistence.entity.ChatMessageEntity;
import com.shana.infrastructure.persistence.mapper.ChatMessageMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 群聊发言统计工具：回答"群里谁最活跃、最近聊了多少"。
 *
 * <p>它是第一个真实工具，也因此定下了这个项目里工具该长什么样：
 * <ul>
 *   <li><b>只读本群</b>：会话 ID 从 {@link ToolContext} 拿，模型参数里根本没有这个字段，
 *       所以它不可能被诱导去查别的群；</li>
 *   <li><b>参数有边界</b>：天数限制在 1~90，条数固定取前 20。让模型自由指定上限，
 *       等于把"一次查询要跑多久"交给它决定；</li>
 *   <li><b>结果给模型看，不是给人看</b>：纯文本、短行、无表格线。模型读 Markdown 表格
 *       并不比读列表更准，反而更费 token。</li>
 * </ul>
 *
 * <p>放在 infrastructure 而不是 agent：它需要 {@code ChatMessageMapper}，而 agent 模块
 * 只定义扩展点、不碰数据库。工具的实现天然属于"有资源的那一层"。
 */
@Component
public class GroupMessageStatsTool implements Tool {

    private static final String NAME = "group_message_stats";

    private static final int DEFAULT_DAYS = 7;

    private static final int MAX_DAYS = 90;

    /** 只返回发言最多的前若干人：一个 500 人大群全列出来会撑爆上下文，也没有阅读价值。 */
    private static final int TOP_SENDERS = 20;

    private final ChatMessageMapper chatMessageMapper;
    private final ObjectMapper objectMapper;

    public GroupMessageStatsTool(ChatMessageMapper chatMessageMapper, ObjectMapper objectMapper) {
        this.chatMessageMapper = chatMessageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "统计当前群聊最近一段时间内每位成员的发言条数，用来回答“群里谁最活跃”“最近大家聊了多少”这类问题。"
                + "只能查当前所在的群，不能查其他群。没有带发送者标识的历史消息不会被计入。";
    }

    @Override
    public String parametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "days": {
                      "type": "integer",
                      "description": "统计最近多少天，默认 7，范围 1~90",
                      "minimum": 1,
                      "maximum": 90
                    }
                  },
                  "required": []
                }
                """;
    }

    @Override
    public String execute(ToolContext context, String arguments) {
        int days = parseDays(arguments);
        OffsetDateTime from = OffsetDateTime.now().minusDays(days);

        QueryWrapper<ChatMessageEntity> query = new QueryWrapper<ChatMessageEntity>()
                .select("sender_id", "count(*) AS message_count")
                .eq("conversation_id", context.conversationId())
                .isNotNull("sender_id")
                .ge("created_at", from)
                .groupBy("sender_id")
                .orderByDesc("message_count")
                // 常量拼接，不涉及外部输入；MyBatis-Plus 的分页插件在这里只是多余开销
                .last("LIMIT " + TOP_SENDERS);
        List<Map<String, Object>> rows = chatMessageMapper.selectMaps(query);

        if (rows == null || rows.isEmpty()) {
            return "最近 " + days + " 天没有采集到带发送者标识的消息，无法统计。"
                    + "（可能是这段时间没人发言，或消息未带 senderId）";
        }

        long total = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("群 ").append(context.conversationId())
                .append(" 最近 ").append(days).append(" 天发言统计：\n");
        int index = 0;
        for (Map<String, Object> row : rows) {
            long count = toLong(row.get("message_count"));
            total += count;
            sb.append(++index).append(". ")
                    .append(row.get("sender_id")).append("：").append(count).append(" 条\n");
        }
        sb.append("合计 ").append(total).append(" 条，涉及 ").append(rows.size()).append(" 位成员");
        if (rows.size() >= TOP_SENDERS) {
            sb.append("（仅列出前 ").append(TOP_SENDERS).append(" 位）");
        }
        return sb.toString();
    }

    /**
     * 解析天数。
     *
     * <p>模型给的参数不可信：可能缺字段、可能是字符串、可能是 1000。
     * 这里一律回落到默认值或夹到边界，而不是抛异常——参数不完美不该让整次回答失败，
     * 查"最近 7 天"总比告诉用户"你问得不对"有用。
     */
    private int parseDays(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return DEFAULT_DAYS;
        }
        try {
            JsonNode node = objectMapper.readTree(arguments).path("days");
            if (!node.isNumber()) {
                return DEFAULT_DAYS;
            }
            int days = node.asInt(DEFAULT_DAYS);
            if (days < 1) {
                return 1;
            }
            return Math.min(days, MAX_DAYS);
        } catch (Exception e) {
            return DEFAULT_DAYS;
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

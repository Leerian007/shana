package com.shana.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 OneBot v11 的事件 JSON 翻译成 {@link IncomingMessage}。
 *
 * <p>OneBot 的消息体有两种形态，取决于 NapCat 侧的 {@code message_format}：
 * <ul>
 *   <li><b>array</b>（推荐）：{@code [{"type":"at","data":{"qq":"123"}},{"type":"text",...}]}，
 *       结构清晰，@ 和引用都是独立段；</li>
 *   <li><b>string</b>：CQ 码字符串 {@code [CQ:at,qq=123] 你好}，解析全靠正则，转义一多就不可靠。</li>
 * </ul>
 * 两种都支持，是因为默认配置未必是 array；但线上建议在 NapCat 里显式设成 array。
 *
 * <p>本类只做"翻译"，不做"回不回"的判断——把 {@code self_id == sender_id} 这类规则也塞进来，
 * 会让"为什么这条被丢了"变得难以定位。判断统一在 {@link ReplyPolicy}。
 */
public final class OneBotEventParser {

    /** CQ 码：{@code [CQ:at,qq=123]}，参数部分可省略。 */
    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:([a-zA-Z0-9_.-]+)((?:,[^\\]]*)?)]");

    private final ObjectMapper objectMapper;

    public OneBotEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<IncomingMessage> parse(JsonNode event) {
        if (event == null || !"message".equals(text(event, "post_type"))) {
            // message_sent 是我们自己发出去的消息，notice/request 是好友申请之类的事件，都不走问答
            return Optional.empty();
        }

        String messageType = text(event, "message_type");
        if (!"group".equals(messageType) && !"private".equals(messageType)) {
            return Optional.empty();
        }

        long selfId = event.path("self_id").asLong(0);
        if (selfId <= 0) {
            return Optional.empty();
        }

        long senderId = event.path("sender").path("user_id").asLong(event.path("user_id").asLong(0));
        Long groupId = null;
        if ("group".equals(messageType)) {
            long raw = event.path("group_id").asLong(0);
            if (raw <= 0) {
                return Optional.empty();
            }
            groupId = raw;
        }

        String messageId = firstNonBlank(text(event, "message_id"), text(event, "message_seq"));
        Body body = event.path("message").isArray()
                ? parseArray(event.path("message"))
                : parseCqString(event.path("message").asText(text(event, "raw_message")));

        return Optional.of(new IncomingMessage(
                messageId,
                selfId,
                senderId,
                groupId,
                text(event, "sub_type"),
                body.text(),
                body.mentionsSelf(selfId),
                body.quotedMessageId()));
    }

    private Body parseArray(JsonNode segments) {
        StringBuilder text = new StringBuilder();
        List<String> mentionTargets = new ArrayList<>();
        Long quoted = null;

        for (JsonNode segment : segments) {
            String type = text(segment, "type");
            JsonNode data = segment.path("data");
            switch (type) {
                case "text" -> text.append(data.path("text").asText(""));
                // qq 在不同实现里可能是字符串也可能是数字，asText 对两者都成立
                case "at" -> mentionTargets.add(data.path("qq").asText(""));
                case "reply" -> quoted = asLongOrNull(data.path("id").asText(null));
                default -> {
                    // 图片、表情、语音等：不参与问答正文，但也不该让整条消息失败
                }
            }
        }
        return new Body(text.toString().strip(), mentionTargets, quoted);
    }

    private Body parseCqString(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Body("", List.of(), null);
        }
        StringBuilder text = new StringBuilder();
        List<String> mentionTargets = new ArrayList<>();
        Long quoted = null;
        Matcher matcher = CQ_CODE.matcher(raw);
        int cursor = 0;
        while (matcher.find()) {
            text.append(raw, cursor, matcher.start());
            cursor = matcher.end();
            String type = matcher.group(1);
            String params = matcher.group(2);
            if ("at".equals(type)) {
                mentionTargets.add(param(params, "qq"));
            } else if ("reply".equals(type)) {
                quoted = asLongOrNull(param(params, "id"));
            }
        }
        text.append(raw.substring(cursor));
        return new Body(unescape(text.toString()).strip(), mentionTargets, quoted);
    }

    private static String param(String params, String key) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        for (String pair : params.substring(1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return unescape(pair.substring(eq + 1));
            }
        }
        return null;
    }

    /** CQ 码里 {@code [ ] , &} 会被转义，还原后再送进模型，否则回复里会出现 &#91; 这种噪声。 */
    private static String unescape(String value) {
        return value.replace("&#91;", "[").replace("&#93;", "]")
                .replace("&#44;", ",").replace("&amp;", "&");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static Long asLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 中间结果。{@code mentionTarget} 存的是 at 段的 qq，最后统一与 self_id 比对：
     * 解析阶段拿不到 self_id 也没关系，只要把"at 了谁"原样留下即可。
     */
    private record Body(String text, List<String> mentionTargets, Long quotedMessageId) {

        /** 一条消息可能同时 at 了多个人（包括 @全体成员），任意一个命中自己就算叫我。 */
        boolean mentionsSelf(long selfId) {
            String target = String.valueOf(selfId);
            return mentionTargets.stream().anyMatch(target::equals);
        }
    }
}

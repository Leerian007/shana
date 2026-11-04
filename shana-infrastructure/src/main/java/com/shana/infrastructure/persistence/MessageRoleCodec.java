package com.shana.infrastructure.persistence;

import com.shana.model.ChatRole;

import java.util.Locale;

/**
 * 角色在 Java 枚举与数据库字符串之间的转换。
 *
 * <p>入库统一小写，与主流 Chat API 的写法一致，也避免大小写不一致导致按角色查不出来。
 * 抽出来是因为现在有两处要读这张表（上下文实现、归档实现），
 * 解析规则必须只有一份——否则两边的容错行为迟早分叉。
 */
final class MessageRoleCodec {

    private MessageRoleCodec() {
    }

    static String toStorage(ChatRole role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    static ChatRole fromStorage(String role) {
        try {
            return ChatRole.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            // 角色是入库前自己写的，出现未知值说明数据被外部改过或迁移脚本有问题，
            // 这里直接失败比静默当成 user 更安全
            throw new IllegalStateException("数据库中存在无法识别的消息角色: " + role, e);
        }
    }
}

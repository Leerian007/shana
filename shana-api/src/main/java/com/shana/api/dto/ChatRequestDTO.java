package com.shana.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起一次问答的请求体。
 *
 * <p>只做"能不能收"的校验（非空、长度），业务规则不在 DTO 里判断。
 *
 * @param conversationId 会话 ID，例如群聊 ID
 * @param message        用户消息正文
 */
public record ChatRequestDTO(

        @NotBlank(message = "conversationId 不能为空")
        @Size(max = 64, message = "conversationId 长度不能超过 64")
        String conversationId,

        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "message 长度不能超过 2000")
        String message) {
}

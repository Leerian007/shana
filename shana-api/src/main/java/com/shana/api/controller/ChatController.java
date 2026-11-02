package com.shana.api.controller;

import com.shana.api.dto.ChatRequestDTO;
import com.shana.api.vo.ChatReplyVO;
import com.shana.common.api.Result;
import com.shana.core.chat.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群聊问答接口。
 *
 * <p>Controller 只做"收参数 → 调用服务 → 转响应"，业务编排在 {@link ChatService} 里。
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public Result<ChatReplyVO> chat(@Valid @RequestBody ChatRequestDTO request) {
        return Result.success(ChatReplyVO.from(chatService.reply(request.conversationId(), request.message())));
    }
}

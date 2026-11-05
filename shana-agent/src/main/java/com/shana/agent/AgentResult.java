package com.shana.agent;

import com.shana.model.TokenUsage;

import java.util.List;

/**
 * 一次 Agent 调用的结果。
 *
 * @param content  最终回复正文
 * @param usage    本次执行<b>所有轮次</b>累加的 token 用量；只看最后一轮会低估成本
 * @param toolsUsed 按执行顺序记录的工具名，用于"模型到底用没用工具"的可观测性
 * @param rounds   实际发生的模型调用轮数，1 表示没走工具
 */
public record AgentResult(String content, TokenUsage usage, List<String> toolsUsed, int rounds) {

    public AgentResult {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Agent 结果正文不能为空");
        }
        if (usage == null) {
            usage = TokenUsage.unknown();
        }
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
    }
}

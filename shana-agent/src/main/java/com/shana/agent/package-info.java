/**
 * Agent 模块：负责 Agent 循环、工具调用与任务编排。
 *
 * <p>第一阶段只提供工具扩展点（{@link com.shana.agent.tool.Tool} 与
 * {@link com.shana.agent.tool.ToolRegistry}），尚未实现 Agent 主循环。
 * 后续计划：单轮工具调用 → 多轮思考-行动循环 → 群聊场景的任务编排。
 */
package com.shana.agent;

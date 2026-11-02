package com.shana.agent.tool;

/**
 * Agent 可调用的工具。
 *
 * <p>后续的检索、群聊统计、提醒等能力都以 Tool 的形式接入，由模型通过工具调用（function calling）选择执行。
 * 当前阶段只定义扩展点，暂无内置工具。
 *
 * <p>实现约定：线程安全；入参使用 JSON 字符串，便于直接对接模型返回的 arguments；
 * 失败时抛出运行时异常，由编排层转换为工具错误信息回填给模型。
 */
public interface Tool {

    /** 工具名，全局唯一，需与模型看到的名称一致，建议使用下划线命名。 */
    String name();

    /** 工具说明，会作为 prompt 的一部分交给模型，需要写清楚用途与参数。 */
    String description();

    /**
     * 执行工具。
     *
     * @param arguments JSON 格式入参，无参时为空字符串
     * @return 执行结果，通常是文本或 JSON 字符串
     */
    String execute(String arguments);
}

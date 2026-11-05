package com.shana.agent;

/**
 * Agent 执行入口。
 *
 * <p>刻意只有"执行"这一个动作：Agent 的价值在于"给一段 prompt，拿回一段答复，中间可能需要调工具"，
 * 至于调几个、怎么调，是实现的自由。暴露更多方法会让调用方开始依赖具体循环细节，
 * 以后想换成"多 Agent 协作"或"先规划再执行"就得改调用方。
 */
public interface Agent {

    /**
     * 执行一次对话，必要时在中间调用工具，并把过程实时回调给 {@code listener}。
     *
     * <p>流式与否由实现决定（取决于模型客户端是否具备流式能力），调用方不需要区分：
     * 拿到的 {@link AgentResult} 完全相同，只是"什么时候能看到文字"变了。
     *
     * @param request  初始 prompt 与工具上下文
     * @param listener 过程回调，传 {@link AgentListener#NOOP} 表示不关心中间过程
     * @return 最终答复、累加的 token 用量与工具调用轨迹
     * @throws com.shana.common.exception.BusinessException 没有可用模型适配器、上游失败或轮次超限时抛出
     */
    AgentResult run(AgentRequest request, AgentListener listener);

    /** 不关心中间过程时的简写。 */
    default AgentResult run(AgentRequest request) {
        return run(request, AgentListener.NOOP);
    }
}

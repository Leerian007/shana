package com.shana.agent.tool;

import com.shana.model.ToolSpec;

/**
 * Agent 可调用的工具。
 *
 * <p>检索、群聊统计、提醒等能力都以 Tool 的形式接入，由模型通过工具调用（function calling）选择执行。
 * 新增工具只需实现本接口并注册为 Spring Bean，{@link ToolRegistry} 会自动收集。
 *
 * <p>实现约定：
 * <ul>
 *   <li><b>线程安全</b>：同一个 Bean 会被并发的多次对话同时调用，不要持有可变状态；</li>
 *   <li><b>入参是 String</b>：模型返回的 arguments 是一段 JSON 文本，可能为空、可能不合法，
 *       工具自己负责解析并校验；</li>
 *   <li><b>失败就抛异常</b>：不要返回 {@code "error: ..."} 这种字符串。抛出异常会被编排层
 *       统一转换成工具错误回填给模型，模型才知道这次调用没成功；而把错误当成正常结果返回，
 *       模型会当作事实继续推理。</li>
 * </ul>
 */
public interface Tool {

    /** 工具名，全局唯一，需与模型看到的名称一致，建议使用下划线命名。 */
    String name();

    /** 工具说明，会原样作为 prompt 交给模型。写清楚"什么时候该用"比写实现细节更有用。 */
    String description();

    /**
     * 参数 JSON Schema，决定模型怎么填参数。
     * <p>无参工具保持默认实现即可，不要返回 null。
     */
    default String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    /**
     * 执行工具。
     *
     * @param context   请求上下文，提供当前会话等信息；模型无法伪造
     * @param arguments JSON 格式入参，无参时为空字符串
     * @return 执行结果，通常是文本或 JSON 字符串；返回 null 或空串会被编排层替换为占位说明
     */
    String execute(ToolContext context, String arguments);

    /** 交给模型的说明书，注册时一次性构建。 */
    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parametersSchema());
    }
}

package com.shana.model;

/**
 * 对话角色，与主流 Chat API 的角色语义保持一致。
 */
public enum ChatRole {

    /** 系统提示词：设定助手的人设与群聊规则 */
    SYSTEM,

    /** 用户（群成员）消息 */
    USER,

    /** 模型回复 */
    ASSISTANT,

    /** 工具调用结果，回填给模型 */
    TOOL
}

package com.shana.memory;

/**
 * 关键信息的类别。
 *
 * <p>类别是为了"取用时能挑"而不是为了分类好看：拼 prompt 时可以按优先级取舍
 * （DECISION 和 PERSON 通常比 FACT 更有用），后续做画像时也能按类型过滤。
 *
 * <p>刻意保持很短的取值集合。类别一多，模型抽取时的判断成本会上升，
 * 出错率反而比"全都归到 FACT"更高。
 */
public enum FactKind {

    /** 某人是谁、在做什么、和谁有关系 */
    PERSON,

    /** 某人稳定表现出的偏好或习惯 */
    PREFERENCE,

    /** 约定、决定、结论（"下周三之前交"、"就用方案 B"） */
    DECISION,

    /** 其它值得记住的客观信息 */
    FACT
}

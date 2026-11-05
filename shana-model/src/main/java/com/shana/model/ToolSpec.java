package com.shana.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * 交给模型的工具说明。
 *
 * <p>这是<b>模型侧</b>的概念：它决定"模型知不知道有这个工具、该传什么参数"。工具怎么执行是
 * {@code com.shana.agent.tool.Tool} 的事，两者刻意分开——模型只需要看到说明书，
 * 不需要看到实现类。
 *
 * <p>参数用 JSON Schema 字符串而不是 Java 类型：schema 是任意的嵌套结构（对象、数组、枚举、
 * 必填项），用 Java 类型去描述它只会造出一套半吊子的 DSL。代价是 schema 写错时上游只会返回一句
 * 含糊的 400，所以这里在构造时就校验它必须是一个合法的 JSON 对象——把错误提前到启动期。
 *
 * @param name             工具名，需满足 {@code ^[a-zA-Z0-9_-]{1,64}$}（OpenAI 协议的限制）
 * @param description      工具用途，会原样进入 prompt，写清楚"什么时候该用"比写实现细节更有用
 * @param parametersSchema 参数 JSON Schema，无参工具也要给空对象 schema
 */
public record ToolSpec(String name, String description, String parametersSchema) {

    /** 上游对工具名的硬性限制；用中文或空格命名会在第一次调用时才发现，不如启动就拦下。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private static final ObjectMapper JSON = new ObjectMapper();

    public ToolSpec {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "工具名只能包含字母、数字、下划线和短横线，长度 1~64: " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具说明不能为空，模型只能靠它判断该不该调用: " + name);
        }
        if (parametersSchema == null || parametersSchema.isBlank()) {
            parametersSchema = EMPTY_SCHEMA;
        }
        if (!isJsonObject(parametersSchema)) {
            throw new IllegalArgumentException("工具参数 schema 不是合法的 JSON 对象: " + name);
        }
    }

    /** 无参工具。 */
    public static ToolSpec of(String name, String description) {
        return new ToolSpec(name, description, EMPTY_SCHEMA);
    }

    private static boolean isJsonObject(String json) {
        try {
            return JSON.readTree(json).isObject();
        } catch (Exception e) {
            return false;
        }
    }
}

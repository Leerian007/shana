package com.shana.agent.tool;

import com.shana.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册表：收集容器中所有 {@link Tool} 实现，供 Agent 按名称查找、按列表交给模型。
 *
 * <p>两件事在<b>构造期</b>完成，而不是等到第一次调用：
 * <ul>
 *   <li>重名检测——两个工具同名时模型只会看到其中一个，另一个永远调不到，而且没人会收到报错；</li>
 *   <li>说明书构建——{@link Tool#spec()} 顺带校验工具名与参数 schema，
 *       写错时启动就失败，而不是等到对话时才收到一句上游的 400。</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools;
    private final List<ToolSpec> specs;

    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> registered = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Tool existing = registered.putIfAbsent(tool.name(), tool);
            if (existing != null) {
                throw new IllegalStateException("工具重复注册: " + tool.name()
                        + " -> " + existing.getClass().getName() + ", " + tool.getClass().getName());
            }
        }
        // 用 unmodifiableMap 而不是 Map.copyOf，以保留注册顺序
        this.tools = Collections.unmodifiableMap(registered);
        this.specs = registered.values().stream().map(Tool::spec).toList();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 全部工具的只读副本，保持注册顺序。 */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public Set<String> names() {
        return tools.keySet();
    }

    /** 交给模型的工具说明书，顺序与注册顺序一致，便于日志与 prompt 对照。 */
    public List<ToolSpec> specs() {
        return specs;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }
}

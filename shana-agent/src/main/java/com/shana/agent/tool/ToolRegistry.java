package com.shana.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册表：收集容器中所有 {@link Tool} 实现，供 Agent 按名称查找。
 *
 * <p>当前没有任何工具实现，{@link #all()} 返回空集合；新增工具只需实现接口并注册为 Bean。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools;

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
}

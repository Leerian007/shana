package com.shana.core.memory;

import com.shana.memory.MemoryFact;
import com.shana.memory.MemorySnapshot;

/**
 * 把长期记忆渲染成一段可直接塞进 system prompt 的文本。
 *
 * <p>单独抽出来是因为它决定"模型看到什么"，值得单独测；
 * 塞进 {@code ChatServiceImpl} 里会让那个类既管编排又管文案。
 *
 * <p>不渲染 {@link MemoryFact#kind()}：类别是给分析和后台用的元数据，
 * 对"怎么回答"没有帮助，写进 prompt 只会白烧 token。
 */
public final class MemoryPromptRenderer {

    private MemoryPromptRenderer() {
    }

    /**
     * @return 渲染结果；没有任何记忆时返回空串，调用方据此跳过拼接
     */
    public static String render(MemorySnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder("【群聊长期记忆】以下是你之前记住的信息，回答时可以自然使用，不必逐条复述。\n");

        if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
            text.append("摘要：").append(snapshot.summary().strip()).append('\n');
        }

        if (!snapshot.facts().isEmpty()) {
            text.append("已知信息：\n");
            for (MemoryFact fact : snapshot.facts()) {
                text.append("- ");
                if (!fact.subject().isBlank()) {
                    text.append(fact.subject()).append("：");
                }
                text.append(fact.content()).append('\n');
            }
        }

        return text.toString().strip();
    }
}

/**
 * 记忆模块：管理群聊的短期上下文与后续的长期记忆。
 *
 * <p>第一阶段只提供内存实现 {@link com.shana.memory.InMemoryConversationMemory}，
 * 用于跑通"拼接上下文"的链路。长期记忆（对话摘要、用户画像、向量检索）留待后续迭代。
 */
package com.shana.memory;

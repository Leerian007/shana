package com.shana.rag;

/**
 * 切分后的文本片段（尚未向量化）。
 *
 * @param index   在文档内的顺序，从 0 开始；检索命中后要按它回溯上下文
 * @param content 片段正文
 */
public record DocumentChunk(int index, String content) {
}

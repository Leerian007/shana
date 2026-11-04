package com.shana.rag;

/**
 * 入库请求。
 *
 * @param title   文档标题，检索结果里作为出处展示，必填
 * @param source  来源标识（文件路径、URL、群号等），可空
 * @param content 正文，纯文本
 */
public record IngestCommand(String title, String source, String content) {
}

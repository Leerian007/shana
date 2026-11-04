package com.shana.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 上传一篇知识库文档的请求体。
 *
 * @param title   文档标题，检索结果里作为出处展示
 * @param source  来源标识（文件路径、URL、群号），可选
 * @param content 正文，纯文本。
 *                上限 20 万字符≈ 数千个 chunk，已经远超单次入库的合理规模；
 *                再大应该拆成多篇，否则一次请求要跑几分钟，超时后重试又会重复向量化。
 */
public record IngestDocumentDTO(

        @NotBlank(message = "title 不能为空")
        @Size(max = 200, message = "title 长度不能超过 200")
        String title,

        @Size(max = 500, message = "source 长度不能超过 500")
        String source,

        @NotBlank(message = "content 不能为空")
        @Size(max = 200_000, message = "content 长度不能超过 200000")
        String content) {
}

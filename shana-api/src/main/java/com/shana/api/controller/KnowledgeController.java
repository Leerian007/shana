package com.shana.api.controller;

import com.shana.api.dto.IngestDocumentDTO;
import com.shana.api.vo.IngestResultVO;
import com.shana.api.vo.RetrievedChunkVO;
import com.shana.common.api.ErrorCode;
import com.shana.common.api.Result;
import com.shana.common.exception.BusinessException;
import com.shana.rag.IngestCommand;
import com.shana.rag.KnowledgeBase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库接口：上传文档、检索调试、删除文档。
 *
 * <p>检索接口的存在意义是"可观测"：不提供它，RAG 出问题时只能靠看模型回答猜
 * 是"没检索到"还是"检索到了但模型没用"。有了它，先确认召回，再怀疑模型。
 *
 * <p>{@link KnowledgeBase} 用 {@link ObjectProvider} 注入而不是必填参数：
 * 知识库只在 {@code store=jdbc} 下装配，Redis / 内存模式下整个 Bean 不存在。
 * 写成必填会让"没开知识库"变成启动失败——一个可选能力不该拖垮整个应用。
 * 代价是接口会返回 503 而不是 404，这里选 503：功能是"暂时不可用"，不是"没有这个接口"。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
@Validated
public class KnowledgeController {

    private final ObjectProvider<KnowledgeBase> knowledgeBaseProvider;

    @PostMapping("/documents")
    public Result<IngestResultVO> ingest(@Valid @RequestBody IngestDocumentDTO request) {
        return Result.success(IngestResultVO.from(knowledgeBase().ingest(
                new IngestCommand(request.title(), request.source(), request.content()))));
    }

    /**
     * @param topK 传 0 表示用配置里的默认值（{@code shana.rag.top-k}）
     */
    @GetMapping("/search")
    public Result<List<RetrievedChunkVO>> search(@RequestParam("q") @NotBlank String query,
                                                 @RequestParam(name = "topK", defaultValue = "0") int topK) {
        List<RetrievedChunkVO> chunks = knowledgeBase().search(query, topK).stream()
                .map(RetrievedChunkVO::from)
                .toList();
        return Result.success(chunks);
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable("id") long documentId) {
        if (!knowledgeBase().delete(documentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在: " + documentId);
        }
        return Result.success();
    }

    private KnowledgeBase knowledgeBase() {
        KnowledgeBase knowledgeBase = knowledgeBaseProvider.getIfAvailable();
        if (knowledgeBase == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "知识库未启用：需要 shana.memory.store=jdbc（知识库必须落库，Redis/内存模式不支持）");
        }
        return knowledgeBase;
    }
}

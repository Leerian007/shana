package com.shana.application;

import com.shana.rag.IngestCommand;
import com.shana.rag.IngestResult;
import com.shana.rag.KnowledgeBase;
import com.shana.rag.store.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库接口的 HTTP 层测试：知识库实现换成替身，只验证参数绑定与响应结构。
 *
 * <p>不用真实数据库和真实 embedding：接口层的职责是"收参数、转结构"，
 * 召回质量与 SQL 行为分别由 `KnowledgeBaseImplTest` 和手动联调覆盖。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeBase knowledgeBase;

    @Test
    @DisplayName("POST /api/v1/knowledge/documents 返回文档 ID 与切分数")
    void shouldIngestDocument() throws Exception {
        given(knowledgeBase.ingest(any(IngestCommand.class)))
                .willReturn(new IngestResult(1L, 7, false));

        mockMvc.perform(post("/api/v1/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Flink 运维手册","source":"docs/flink.md","content":"checkpoint 需要先配置 state backend"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.documentId").value(1))
                .andExpect(jsonPath("$.data.chunkCount").value(7))
                .andExpect(jsonPath("$.data.duplicate").value(false));
    }

    @Test
    @DisplayName("重复上传时 duplicate=true，chunkCount 为 0")
    void shouldReportDuplicate() throws Exception {
        given(knowledgeBase.ingest(any(IngestCommand.class)))
                .willReturn(new IngestResult(3L, 0, true));

        mockMvc.perform(post("/api/v1/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"同一份资料","content":"内容"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.chunkCount").value(0));
    }

    @Test
    @DisplayName("正文为空时返回 400，不进入业务层")
    void shouldRejectBlankContent() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"标题","content":"  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("GET /api/v1/knowledge/search 返回片段与相似度")
    void shouldSearch() throws Exception {
        given(knowledgeBase.search("checkpoint 怎么配", 0)).willReturn(List.of(
                new RetrievedChunk(11L, 1L, "Flink 运维手册", "checkpoint 依赖 state backend", 0.83)));

        mockMvc.perform(get("/api/v1/knowledge/search").param("q", "checkpoint 怎么配"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].documentId").value(1))
                .andExpect(jsonPath("$.data[0].documentTitle").value("Flink 运维手册"))
                .andExpect(jsonPath("$.data[0].score").value(0.83));
    }

    @Test
    @DisplayName("缺少查询词时返回 400")
    void shouldRejectSearchWithoutQuery() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("删除不存在的文档返回 404")
    void shouldReturnNotFoundWhenDeleting() throws Exception {
        given(knowledgeBase.delete(99L)).willReturn(false);

        mockMvc.perform(delete("/api/v1/knowledge/documents/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("空检索结果返回空数组，而不是报错")
    void shouldReturnEmptyArray() throws Exception {
        given(knowledgeBase.search(anyString(), anyInt())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/knowledge/search").param("q", "无关问题"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}

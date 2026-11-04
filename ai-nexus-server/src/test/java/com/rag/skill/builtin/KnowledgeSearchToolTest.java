package com.rag.skill.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.ai.EmbeddingService;
import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.KnowledgeBase;
import com.rag.mapper.ChunkMapper;
import com.rag.rag.KeywordSearchService;
import com.rag.rag.QdrantService;
import com.rag.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库检索技能测试：验证参数校验、权限拦截、混合召回和降级逻辑（不依赖真实检索服务）
 */
class KnowledgeSearchToolTest {

    private KnowledgeBaseService knowledgeBaseService;
    private EmbeddingService embeddingService;
    private QdrantService qdrantService;
    private KeywordSearchService keywordSearchService;
    private ChunkMapper chunkMapper;
    private KnowledgeSearchTool tool;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        embeddingService = mock(EmbeddingService.class);
        qdrantService = mock(QdrantService.class);
        keywordSearchService = mock(KeywordSearchService.class);
        chunkMapper = mock(ChunkMapper.class);
        tool = new KnowledgeSearchTool(knowledgeBaseService, embeddingService, qdrantService, keywordSearchService, chunkMapper);
    }

    private KnowledgeBase kb(Long id, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        return kb;
    }

    private KbChunk chunk(Long id, String fileName, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setFileName(fileName);
        chunk.setContent(content);
        return chunk;
    }

    @Test
    void missingUserIdReturnsHint() throws Exception {
        String result = tool.execute(objectMapper.readTree("{\"query\":\"合同金额\"}"));
        assertTrue(result.contains("未获取到用户身份"));
    }

    @Test
    void missingQueryReturnsHint() throws Exception {
        String result = tool.execute(objectMapper.readTree("{\"userId\":1}"));
        assertTrue(result.contains("缺少检索词"));
    }

    @Test
    void kbWithoutAccessReturnsHint() throws Exception {
        doThrow(new BusinessException(403, "无权限访问")).when(knowledgeBaseService).checkAccess(9L, 1L);
        String result = tool.execute(objectMapper.readTree("{\"userId\":1,\"query\":\"合同金额\",\"kbId\":9}"));
        assertTrue(result.contains("无法访问"));
    }

    @Test
    void noVisibleKbReturnsHint() throws Exception {
        when(knowledgeBaseService.getVisible(1L)).thenReturn(List.of());
        String result = tool.execute(objectMapper.readTree("{\"userId\":1,\"query\":\"合同金额\"}"));
        assertTrue(result.contains("没有可访问的知识库"));
    }

    @Test
    void hybridSearchReturnsSourcedSnippets() throws Exception {
        when(knowledgeBaseService.getVisible(1L)).thenReturn(List.of(kb(1L, "产品资料库")));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(qdrantService.searchWithScore(any(), anyInt(), anyLong()))
                .thenReturn(List.of(new QdrantService.SearchResult(1L, 0.8f)));
        when(chunkMapper.findByIdAndKbId(1L, 1L)).thenReturn(chunk(1L, "产品手册.pdf", "本产品支持七天无理由退货。"));
        when(keywordSearchService.search(anyString(), anyLong(), anyInt()))
                .thenReturn(List.of(chunk(1L, "产品手册.pdf", "本产品支持七天无理由退货。")));

        String result = tool.execute(objectMapper.readTree("{\"userId\":1,\"query\":\"退货政策\"}"));

        assertTrue(result.contains("检索到 1 个相关片段"));
        assertTrue(result.contains("产品资料库"));
        assertTrue(result.contains("产品手册.pdf"));
        assertTrue(result.contains("七天无理由退货"));
        assertTrue(result.contains("HYBRID"));
    }

    @Test
    void vectorDownFallsBackToKeyword() throws Exception {
        when(knowledgeBaseService.getVisible(1L)).thenReturn(List.of(kb(1L, "制度库")));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(qdrantService.searchWithScore(any(), anyInt(), anyLong())).thenThrow(new RuntimeException("qdrant down"));
        when(keywordSearchService.search(anyString(), anyLong(), anyInt()))
                .thenReturn(List.of(chunk(2L, "考勤制度.docx", "迟到三十分钟视为旷工。")));

        String result = tool.execute(objectMapper.readTree("{\"userId\":1,\"query\":\"迟到怎么算\"}"));

        assertTrue(result.contains("考勤制度.docx"));
        assertTrue(result.contains("迟到三十分钟视为旷工"));
        assertTrue(result.contains("KEYWORD"));
    }

    @Test
    void embeddingDownStillSearchesByKeyword() throws Exception {
        when(knowledgeBaseService.getVisible(1L)).thenReturn(List.of(kb(1L, "制度库")));
        when(embeddingService.embed(anyString())).thenThrow(new RuntimeException("embed down"));
        when(keywordSearchService.search(anyString(), anyLong(), anyInt()))
                .thenReturn(List.of(chunk(3L, "报销制度.docx", "发票需在三十天内提交。")));

        String result = tool.execute(objectMapper.readTree("{\"userId\":1,\"query\":\"报销时限\"}"));

        assertTrue(result.contains("发票需在三十天内提交"));
        assertFalse(result.contains("HYBRID"));
    }

    @Test
    void noResultsReturnsHonestHint() throws Exception {
        when(knowledgeBaseService.getVisible(1L)).thenReturn(List.of(kb(1L, "空库")));
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(keywordSearchService.search(anyString(), anyLong(), anyInt())).thenReturn(List.of());

        String result = tool.execute(objectMapper.readTree("{\"userId\":1,\"query\":\"不存在的内容\"}"));

        assertTrue(result.contains("没有检索到"));
        assertTrue(result.contains("不要编造"));
    }
}

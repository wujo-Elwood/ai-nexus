package com.rag.kb;

import com.rag.common.BusinessException;
import com.rag.entity.KbChunk;
import com.rag.entity.KnowledgeBase;
import com.rag.mapper.ChunkMapper;
import com.rag.mapper.KnowledgeBaseMapper;
import com.rag.service.KnowledgeBaseService;
import com.rag.service.ModelProviderService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识库摘要服务测试。 */
class KnowledgeSummaryServiceTest {

    /** 没有已完成文档时应返回明确业务错误。 */
    @Test
    void shouldRejectSummaryWhenKnowledgeBaseHasNoCompletedChunks() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        when(chunkMapper.findCompletedCurrentByKbId(8L)).thenReturn(List.of());
        KnowledgeSummaryService service = new KnowledgeSummaryService(chunkMapper, kbMapper, kbService, mock(ModelProviderService.class));

        assertThrows(BusinessException.class, () -> service.generateSummary(8L, 2L));
    }

    /** 摘要生成成功后应只保存最终摘要。 */
    @Test
    void shouldPersistGeneratedSummary() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        KnowledgeBaseMapper kbMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        ModelProviderService providerService = mock(ModelProviderService.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(8L);
        KbChunk chunk = new KbChunk();
        chunk.setContent("员工年假按照工龄计算，满一年可享受十天年假。");
        when(chunkMapper.findCompletedCurrentByKbId(8L)).thenReturn(List.of(chunk));
        when(kbMapper.findById(8L)).thenReturn(kb);
        KnowledgeSummaryService service = new KnowledgeSummaryService(chunkMapper, kbMapper, kbService, providerService,
                (prompt, provider) -> prompt.contains("员工年假") ? "员工年假按照工龄计算。" : "");

        service.generateSummary(8L, 2L);

        verify(kbMapper).updateSummary(8L, "员工年假按照工龄计算。");
    }
}

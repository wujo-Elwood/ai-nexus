package com.rag.ai;

import com.rag.entity.KbChunk;
import com.rag.kb.KbConflict;
import com.rag.mapper.ChunkMapper;
import com.rag.rag.KeywordSearchService;
import com.rag.rag.QueryRewriter;
import com.rag.rag.Reranker;
import com.rag.rag.RetrievalCache;
import com.rag.rag.RetrievalCandidate;
import com.rag.rag.ContextCompressor;
import com.rag.service.ModelProviderService;
import com.rag.service.UsageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 冲突配对补齐测试：验证未召回的配对切片会被补进候选，并纳入豁免去重的编号集合。
 */
class ChatServiceConflictAmplifyTest {

    @Test
    void shouldFetchMissingConflictPartnerAndProtectBothSides() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        ChatService service = newService(chunkMapper);
        // 2024 版切片没被检索召回，但库里存在
        KbChunk partner = chunk(271L, "员工年假为 10 天");
        when(chunkMapper.findByIdAndKbId(271L, 27L)).thenReturn(partner);

        List<RetrievalCandidate> candidates = new ArrayList<>();
        candidates.add(candidate(chunk(270L, "员工年假为 5 天"), 0.736f));

        Set<Long> protectedIds = invokeAmplify(service, candidates, conflicts(270L, 271L), 27L);

        // 补齐后两版都在候选里，且补进来的那条标记为冲突对照
        assertEquals(2, candidates.size());
        assertEquals(271L, candidates.get(1).getChunk().getId());
        assertEquals("CONFLICT", candidates.get(1).getMatchType());
        // 沿用对侧向量分数，保证它紧挨着原片段出现
        assertEquals(0.736f, candidates.get(1).getVectorScore(), 0.0001f);
        assertTrue(protectedIds.containsAll(Set.of(270L, 271L)));
    }

    @Test
    void shouldNotFetchPartnerAlreadyPresent() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        ChatService service = newService(chunkMapper);

        List<RetrievalCandidate> candidates = new ArrayList<>();
        candidates.add(candidate(chunk(270L, "员工年假为 5 天"), 0.736f));
        candidates.add(candidate(chunk(271L, "员工年假为 10 天"), 0.735f));

        Set<Long> protectedIds = invokeAmplify(service, candidates, conflicts(270L, 271L), 27L);

        assertEquals(2, candidates.size());
        assertTrue(protectedIds.containsAll(Set.of(270L, 271L)));
        // 已经在候选里就不能再查库补一份，否则同一片段会重复进入上下文
        org.mockito.Mockito.verify(chunkMapper, org.mockito.Mockito.never()).findByIdAndKbId(anyLong(), any());
    }

    @Test
    void shouldDoNothingWithoutConflicts() {
        ChunkMapper chunkMapper = mock(ChunkMapper.class);
        ChatService service = newService(chunkMapper);

        List<RetrievalCandidate> candidates = new ArrayList<>();
        candidates.add(candidate(chunk(270L, "员工年假为 5 天"), 0.736f));

        Set<Long> protectedIds = invokeAmplify(service, candidates, new HashMap<>(), 27L);

        assertEquals(1, candidates.size());
        assertTrue(protectedIds.isEmpty());
    }

    private Set<Long> invokeAmplify(ChatService service, List<RetrievalCandidate> candidates,
                                    Map<Long, KbConflict> conflicts, Long kbId) {
        @SuppressWarnings("unchecked")
        Set<Long> result = ReflectionTestUtils.invokeMethod(service, "amplifyConflictCandidates",
                candidates, conflicts, kbId);
        return result;
    }

    private Map<Long, KbConflict> conflicts(Long left, Long right) {
        KbConflict conflict = new KbConflict();
        conflict.setChunkAId(left);
        conflict.setChunkBId(right);
        conflict.setConflictUnit("天");
        conflict.setValueA("5");
        conflict.setValueB("10");
        Map<Long, KbConflict> map = new HashMap<>();
        map.put(left, conflict);
        map.put(right, conflict);
        return map;
    }

    private ChatService newService(ChunkMapper chunkMapper) {
        return new ChatService(mock(com.rag.ai.EmbeddingService.class), mock(com.rag.rag.QdrantService.class),
                chunkMapper, null, mock(ModelProviderService.class), mock(Reranker.class),
                mock(KeywordSearchService.class), mock(UsageService.class), mock(com.rag.rag.PromptGuard.class),
                mock(QueryRewriter.class), mock(RetrievalCache.class), mock(ContextCompressor.class), Runnable::run);
    }

    private RetrievalCandidate candidate(KbChunk chunk, float vectorScore) {
        RetrievalCandidate candidate = new RetrievalCandidate(chunk);
        candidate.setVectorScore(vectorScore);
        candidate.setKeywordScore(0f);
        candidate.setMatchType("VECTOR");
        return candidate;
    }

    private KbChunk chunk(Long id, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setContent(content);
        chunk.setFileId(25L);
        chunk.setFileName("handbook.md");
        chunk.setChunkIndex(0);
        return chunk;
    }
}

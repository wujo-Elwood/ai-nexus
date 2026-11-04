package com.rag.rag;

import com.rag.entity.KbChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检索候选选择器测试
 * 验证低分过滤、关键词保留和相似片段去重
 */
class RetrievalSelectorTest {

    /**
     * 测试关键词命中即使向量分数较低也会保留
     */
    @Test
    void selectorShouldKeepKeywordHitBelowVectorThreshold() {
        RetrievalSelector selector = new RetrievalSelector();
        ReflectionTestUtils.setField(selector, "similarityThreshold", 0.7f);
        RetrievalCandidate candidate = candidate(1L, "员工年假按照工龄计算", 0.2f, 1f, "KEYWORD");

        List<RetrievalCandidate> selected = selector.select(List.of(candidate), 5);

        assertEquals(1, selected.size());
        assertEquals("KEYWORD", selected.get(0).getMatchType());
    }

    /**
     * 测试向量分数低于阈值且没有关键词命中时会被过滤
     */
    @Test
    void selectorShouldRemoveLowScoreVectorOnlyCandidate() {
        RetrievalSelector selector = new RetrievalSelector();
        ReflectionTestUtils.setField(selector, "similarityThreshold", 0.7f);
        RetrievalCandidate candidate = candidate(1L, "无关内容", 0.2f, 0f, "VECTOR");

        List<RetrievalCandidate> selected = selector.select(List.of(candidate), 5);

        assertEquals(0, selected.size());
        assertEquals("BELOW_THRESHOLD", candidate.getFilterReason());
    }

    /**
     * 测试相似文本只保留分数更高的一段
     */
    @Test
    void selectorShouldRemoveNearDuplicateContent() {
        RetrievalSelector selector = new RetrievalSelector();
        ReflectionTestUtils.setField(selector, "similarityThreshold", 0.1f);
        ReflectionTestUtils.setField(selector, "diversityThreshold", 0.8f);
        RetrievalCandidate first = candidate(1L, "员工年假按照工龄计算，工作满一年享受年假", 0.9f, 1f, "HYBRID");
        RetrievalCandidate duplicate = candidate(2L, "员工年假按照工龄计算，工作满一年享受年假", 0.8f, 1f, "HYBRID");

        List<RetrievalCandidate> selected = selector.select(List.of(first, duplicate), 5);

        assertEquals(1, selected.size());
        assertEquals(1L, selected.get(0).getChunk().getId());
        assertEquals("DUPLICATE_CONTENT", duplicate.getFilterReason());
    }

    /**
     * 构造检索候选
     */
    private RetrievalCandidate candidate(Long id, String content, float vectorScore,
                                         float keywordScore, String matchType) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setContent(content);
        RetrievalCandidate candidate = new RetrievalCandidate(chunk);
        candidate.setVectorScore(vectorScore);
        candidate.setKeywordScore(keywordScore);
        candidate.setMatchType(matchType);
        return candidate;
    }
}

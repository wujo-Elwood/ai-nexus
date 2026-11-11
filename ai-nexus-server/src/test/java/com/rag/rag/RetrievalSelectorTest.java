package com.rag.rag;

import com.rag.entity.KbChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * 测试关键词分数归一化：未命中为 0、命中但无相关度退回 1、有相关度按下限比例映射
     */
    @Test
    void normalizeKeywordScoreShouldRespectMatchAndRelevance() {
        // 未命中：即使带相关度也必须是 0，否则向量专属结果会被误判成关键词命中
        assertEquals(0f, RetrievalCandidate.normalizeKeywordScore(false, 5d, 10d), 0.0001f);
        // 命中但走 LIKE 兜底（无相关度）：保持原来"命中即 1 分"的语义
        assertEquals(1f, RetrievalCandidate.normalizeKeywordScore(true, null, 10d), 0.0001f);
        // 命中但整批都没相关度：同样退回 1
        assertEquals(1f, RetrievalCandidate.normalizeKeywordScore(true, 3d, 0d), 0.0001f);
        // 命中且有相关度：映射到 [0.2, 1]，最低也有 0.2 分
        float graded = RetrievalCandidate.normalizeKeywordScore(true, 3d, 12d);
        assertEquals(0.2f + 0.8f * 0.25f, graded, 0.0001f);
        assertTrue(graded > 0f, "关键词分数必须大于 0，否则会失去免阈值资格");
    }

    /**
     * 测试关键词强弱参与融合后能改变排序：弱命中但相关度高的片段应排在前面
     */
    @Test
    void gradedKeywordScoreShouldAffectRanking() {
        RetrievalSelector selector = new RetrievalSelector();
        ReflectionTestUtils.setField(selector, "similarityThreshold", 0.1f);
        // 向量分低但关键词相关度满分
        RetrievalCandidate strongKeyword = candidate(1L, "知识库上传文件大小上限是 50MB", 0.3f, 1f, "HYBRID");
        // 向量分高但关键词只是勉强命中
        RetrievalCandidate weakKeyword = candidate(2L, "上传流程会先校验文件类型再存盘", 0.5f, 0.24f, "HYBRID");

        List<RetrievalCandidate> selected = selector.select(List.of(weakKeyword, strongKeyword), 5);

        assertEquals(2, selected.size());
        assertEquals(1L, selected.get(0).getChunk().getId());
        assertTrue(selected.get(0).getFinalScore() > selected.get(1).getFinalScore());
    }

    /**
     * 测试口径冲突的两个版本被豁免去重：内容几乎相同也必须两段都保留
     */
    @Test
    void selectorShouldKeepBothSidesOfConflictPair() {
        RetrievalSelector selector = new RetrievalSelector();
        ReflectionTestUtils.setField(selector, "similarityThreshold", 0.1f);
        ReflectionTestUtils.setField(selector, "diversityThreshold", 0.8f);
        RetrievalCandidate current = candidate(1L, "员工年假为 5 天，其余条款与上一版一致", 0.9f, 1f, "HYBRID");
        RetrievalCandidate partner = candidate(2L, "员工年假为 10 天，其余条款与上一版一致", 0.85f, 0f, "CONFLICT");
        partner.setVectorScore(0.9f); // 补齐时沿用对侧分数

        List<RetrievalCandidate> selected = selector.select(List.of(current, partner), 5,
                0.1f, 0.6f, 0.4f, 0.8f, java.util.Set.of(1L, 2L));

        // 两版必须同时进上下文，否则用户只能看到其中一版口径
        assertEquals(2, selected.size());
        assertTrue(selected.stream().anyMatch(item -> item.getChunk().getId() == 1L));
        assertTrue(selected.stream().anyMatch(item -> item.getChunk().getId() == 2L));
    }

    /**
     * 测试冲突对照片段豁免低分过滤：补齐进来的切片分数偏低也要保住
     */
    @Test
    void selectorShouldKeepConflictChunkBelowThreshold() {
        RetrievalSelector selector = new RetrievalSelector();
        ReflectionTestUtils.setField(selector, "similarityThreshold", 0.7f);
        RetrievalCandidate partner = candidate(9L, "员工年假为 10 天", 0.2f, 0f, "CONFLICT");

        List<RetrievalCandidate> withoutGuard = selector.select(List.of(partner), 5,
                0.7f, 0.6f, 0.4f, 0.85f, java.util.Set.of());
        assertEquals(0, withoutGuard.size());

        RetrievalCandidate again = candidate(9L, "员工年假为 10 天", 0.2f, 0f, "CONFLICT");
        List<RetrievalCandidate> guarded = selector.select(List.of(again), 5,
                0.7f, 0.6f, 0.4f, 0.85f, java.util.Set.of(9L));
        assertEquals(1, guarded.size());
    }

    /**
     * 测试未登记为冲突的普通近重复内容仍按原规则去重
     */
    @Test
    void selectorShouldStillDeduplicateUnrelatedContent() {
        RetrievalSelector selector = new RetrievalSelector();
        ReflectionTestUtils.setField(selector, "similarityThreshold", 0.1f);
        RetrievalCandidate first = candidate(1L, "员工年假按照工龄计算，工作满一年享受年假", 0.9f, 1f, "HYBRID");
        RetrievalCandidate duplicate = candidate(2L, "员工年假按照工龄计算，工作满一年享受年假", 0.8f, 1f, "HYBRID");

        List<RetrievalCandidate> selected = selector.select(List.of(first, duplicate), 5,
                0.1f, 0.6f, 0.4f, 0.8f, java.util.Set.of(1L));

        assertEquals(1, selected.size());
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

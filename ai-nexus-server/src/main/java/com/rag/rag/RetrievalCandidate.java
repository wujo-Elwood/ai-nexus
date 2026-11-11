package com.rag.rag;

import com.rag.entity.KbChunk;
import lombok.Data;

/**
 * 检索候选及其诊断信息
 */
@Data
public class RetrievalCandidate {
    /** 命中的文本块 */
    private final KbChunk chunk;
    /** 向量相似度分数 */
    private float vectorScore;
    /** 关键词匹配分数 */
    private float keywordScore;
    /** 最终融合分数 */
    private float finalScore;
    /** 命中方式 */
    private String matchType;
    /** 是否进入最终上下文 */
    private boolean selected;
    /** 被过滤原因 */
    private String filterReason;

    /** 关键词分数下限：只要命中就必须大于 0，否则会被误判成"未命中"而失去免阈值资格 */
    public static final float KEYWORD_SCORE_FLOOR = 0.2f;

    /**
     * 创建一个检索候选
     */
    public RetrievalCandidate(KbChunk chunk) {
        this.chunk = chunk;
    }

    /**
     * 把全文索引返回的原始相关度归一化到 [KEYWORD_SCORE_FLOOR, 1]
     * 未命中关键词直接返回 0（必须区分"没命中"和"命中了但没分数"，否则向量专属结果会被误判成关键词命中）；
     * 命中但 relevance 为空（走 LIKE 兜底或旧数据）时退回 1，等同于原来"命中即 1 分"的语义
     */
    public static float normalizeKeywordScore(boolean matched, Double relevance, double maxRelevance) {
        if (!matched) {
            return 0f;
        }
        if (relevance == null || maxRelevance <= 0) {
            return 1f;
        }
        double ratio = Math.max(0d, Math.min(1d, relevance / maxRelevance));
        return (float) (KEYWORD_SCORE_FLOOR + (1f - KEYWORD_SCORE_FLOOR) * ratio);
    }
}

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

    /**
     * 创建一个检索候选
     */
    public RetrievalCandidate(KbChunk chunk) {
        this.chunk = chunk;
    }
}

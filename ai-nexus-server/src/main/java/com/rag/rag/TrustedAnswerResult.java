package com.rag.rag;

import lombok.Getter;

import java.util.List;

/**
 * 可信回答校验结果
 */
@Getter
public class TrustedAnswerResult {
    /** 最终可以展示的回答文本 */
    private final String content;
    /** 是否包含有效知识库依据 */
    private final boolean grounded;
    /** 结果原因 */
    private final String reason;
    /** 校验通过的引用 */
    private final List<KnowledgeCitation> citations;

    /**
     * 创建可信回答结果
     */
    public TrustedAnswerResult(String content, boolean grounded, String reason,
                               List<KnowledgeCitation> citations) {
        this.content = content;
        this.grounded = grounded;
        this.reason = reason;
        this.citations = citations == null ? List.of() : List.copyOf(citations);
    }
}

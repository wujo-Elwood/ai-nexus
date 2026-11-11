package com.rag.rag;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 知识库检索上下文
 * 同时保存模型提示词文本和可展示的结构化引用
 */
@Getter
public class KnowledgeContext {
    /** 纯文本上下文 */
    private final String context;
    /** 带来源标记的上下文 */
    private final String contextWithSources;
    /** 本次检索的引用列表 */
    private final List<KnowledgeCitation> citations;
    /** 实际执行的查询文本 */
    private final String rewrittenQuery;

    /**
     * 创建知识库上下文
     */
    public KnowledgeContext(String context, String contextWithSources,
                            List<KnowledgeCitation> citations, String rewrittenQuery) {
        this.context = context == null ? "" : context;
        this.contextWithSources = contextWithSources == null ? "" : contextWithSources;
        this.citations = citations == null ? List.of() : List.copyOf(citations);
        this.rewrittenQuery = rewrittenQuery == null ? "" : rewrittenQuery;
    }

    /**
     * 创建空上下文
     */
    public static KnowledgeContext empty() {
        return new KnowledgeContext("", "", Collections.emptyList(), "");
    }

    /**
     * 判断是否存在可用于回答的证据
     */
    public boolean hasContext() {
        return !context.isBlank() && !citations.isEmpty();
    }
}

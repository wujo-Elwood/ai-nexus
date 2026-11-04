package com.rag.rag;

import lombok.Data;

/**
 * 知识库回答引用信息
 */
@Data
public class KnowledgeCitation {
    /** 文件编号 */
    private Long fileId;
    /** 文件名称 */
    private String fileName;
    /** 文本块编号 */
    private Long chunkId;
    /** 文本块序号 */
    private Integer chunkIndex;
    /** 来源展示文本 */
    private String source;
    /** 内容预览 */
    private String contentPreview;
    /** 向量分数 */
    private Float vectorScore;
    /** 关键词分数 */
    private Float keywordScore;
    /** 最终融合分数 */
    private Float finalScore;
    /** 命中方式 */
    private String matchType;
}

package com.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库文本块实体类，对应数据库 kb_chunk 表
 * 存储文件拆分后的文本片段，用于向量化检索（RAG）
 */
@Data
public class KbChunk {
    /** 文本块ID，主键 */
    private Long id;
    /** 所属文件ID，关联 kb_file 表 */
    private Long fileId;
    /** 所属文件名称，由文本块查询关联文件表返回 */
    private String fileName;
    /** 文本块在文件中的序号，从0开始 */
    private Integer chunkIndex;
    /** 文本块内容 */
    private String content;
    /** 来源信息，如"文件名, 第N段"，用于引用溯源 */
    private String sourceInfo;
    /** 创建时间 */
    private LocalDateTime createTime;
}

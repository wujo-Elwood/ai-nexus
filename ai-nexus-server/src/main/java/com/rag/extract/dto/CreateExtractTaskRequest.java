package com.rag.extract.dto;

import lombok.Data;

/**
 * 创建抽取任务请求
 */
@Data
public class CreateExtractTaskRequest {

    /**
     * 文档主键
     */
    private Long documentId;

    /**
     * 模板主键
     */
    private Long templateId;
}

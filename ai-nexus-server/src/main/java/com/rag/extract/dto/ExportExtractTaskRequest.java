package com.rag.extract.dto;

import lombok.Data;

/**
 * 导出抽取任务请求
 */
@Data
public class ExportExtractTaskRequest {

    /**
     * 导出类型
     */
    private String exportType;
}

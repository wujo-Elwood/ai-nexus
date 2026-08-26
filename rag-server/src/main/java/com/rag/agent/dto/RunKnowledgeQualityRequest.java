package com.rag.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * 知识库质检运行请求
 * 接收前端选择的知识库、检查模式和测试问题
 */
@Data
public class RunKnowledgeQualityRequest {
    /** 知识库ID */
    private Long kbId;
    /** 检查模式 */
    private String checkMode;
    /** 测试问题列表 */
    private List<String> testQuestions;
    /** 是否保存报告 */
    private Boolean saveReport;
}

package com.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 知识缺口分析报告实体，对应 kb_gap_report 表。 */
@Data
public class KnowledgeGapReport {
    /** 主键。 */
    private Long id;
    /** 分析窗口天数，只允许 7 或 30。 */
    private Integer windowDays;
    /** 参与分析的样本数。 */
    private Integer sampleCount;
    /** 拒答样本数。 */
    private Integer refusalCount;
    /** 低置信度样本数。 */
    private Integer lowConfidenceCount;
    /** 无帮助反馈样本数。 */
    private Integer negativeFeedbackCount;
    /** 报告 JSON 快照。 */
    private String reportJson;
    /** 报告状态。 */
    private String status;
    /** 最近一次错误信息。 */
    private String errorMessage;
    /** 报告生成时间。 */
    private LocalDateTime generatedAt;
}

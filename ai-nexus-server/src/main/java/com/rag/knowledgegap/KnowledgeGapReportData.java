package com.rag.knowledgegap;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 知识缺口报告的可序列化数据结构。 */
@Data
public class KnowledgeGapReportData {
    /** 分析窗口天数。 */
    private int windowDays;
    /** 进入分析的质量样本数。 */
    private int sampleCount;
    /** 拒答样本数。 */
    private int refusalCount;
    /** 低置信度样本数。 */
    private int lowConfidenceCount;
    /** 无帮助反馈样本数。 */
    private int negativeFeedbackCount;
    /** 聚类后的主题列表。 */
    private List<Map<String, Object>> topics = new ArrayList<>();
    /** 分析实现方式。 */
    private String analysisMethod;
    /** 分析异常信息。 */
    private String error;
}

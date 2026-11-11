package com.rag.eval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 评测运行明细：记录每个评测问题这一轮到底检索到了什么
 * 有了明细，才能解释「命中率为什么掉了」——是某条问题没召回，还是召回到了但排在第 4 位
 */
@Data
public class EvalRunItem {
    /** 主键 */
    private Long id;
    /** 所属运行ID */
    private Long runId;
    /** 评测问题ID（问题被删除后为空） */
    private Long caseId;
    /** 评测问题原文 */
    private String question;
    /** 结果状态：HIT / MISS / NO_EVIDENCE / ERROR */
    private String resultStatus;
    /** 检索到的内容摘要（本次回答未调用大模型，此字段留空） */
    private String answer;
    /** 是否命中期望来源：1 命中，0 未命中 */
    private Integer hit;
    /** 引用命中（与 hit 同口径，保留字段兼容旧数据） */
    private Integer citationHit;
    /** 是否检出了可用证据：1 有，0 无 */
    private Integer grounded;
    /** 检索耗时（毫秒） */
    private Long latencyMs;
    /** Token 消耗（纯检索不消耗，固定 0） */
    private Integer tokenCount;
    /** 本次召回结果快照（JSON 数组：文件名、段号、得分） */
    private String sources;
    /** 异常信息 */
    private String errorMessage;
    /** 首个命中期望来源的排名（从 1 开始，0 表示未命中） */
    private Integer matchedRank;
    /** 创建时间 */
    private LocalDateTime createTime;
}

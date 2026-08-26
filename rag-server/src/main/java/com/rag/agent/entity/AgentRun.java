package com.rag.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体运行记录实体
 * 保存每次智能体运行的参数、评分和完整报告
 */
@Data
public class AgentRun {
    /** 运行记录ID */
    private Long id;
    /** 智能体编码 */
    private String agentCode;
    /** 智能体名称 */
    private String agentName;
    /** 运行状态 */
    private String runStatus;
    /** 知识库ID */
    private Long kbId;
    /** 知识库名称快照 */
    private String kbName;
    /** 检查模式 */
    private String checkMode;
    /** 质量评分 */
    private Integer score;
    /** 风险等级 */
    private String riskLevel;
    /** 报告摘要 */
    private String summary;
    /** 运行参数JSON */
    private String requestJson;
    /** 完整报告JSON */
    private String reportJson;
    /** 错误信息 */
    private String errorMessage;
    /** 运行用户 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}

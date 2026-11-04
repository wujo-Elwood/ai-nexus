package com.rag.skill.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体调用统计行（按智能体聚合）
 */
@Data
public class AgentUsageStat {

    /** 智能体编码 */
    private String agentCode;

    /** 调用次数 */
    private Long calls;

    /** 成功次数 */
    private Long successCount;

    /** 失败次数 */
    private Long failedCount;

    /** 平均耗时（毫秒） */
    private Double avgCostMs;

    /** 最近一次调用时间 */
    private LocalDateTime lastCalledAt;
}

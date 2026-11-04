package com.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用量日志实体类，对应数据库 ai_usage_log 表
 * 记录每次大模型调用的 token 消耗和耗时，用于统计分析
 */
@Data
public class UsageLog {
    /** 日志ID，主键 */
    private Long id;
    /** 会话ID */
    private Long sessionId;
    /** 供应商ID */
    private Long providerId;
    /** 供应商名称 */
    private String providerName;
    /** 模型名称 */
    private String model;
    /** 输入 token 数 */
    private Integer promptTokens;
    /** 输出 token 数 */
    private Integer completionTokens;
    /** 总 token 数 */
    private Integer totalTokens;
    /** 调用耗时（毫秒） */
    private Integer durationMs;
    /** 调用状态：SUCCESS / FAILED */
    private String status;
    /** 创建时间 */
    private LocalDateTime createTime;
}

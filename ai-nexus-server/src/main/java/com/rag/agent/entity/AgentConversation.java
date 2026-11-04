package com.rag.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体对话会话实体：用户 + 智能体维度，标题取首条用户消息
 */
@Data
public class AgentConversation {

    /** 主键 */
    private Long id;

    /** 所属用户 */
    private Long userId;

    /** 智能体编码 */
    private String agentCode;

    /** 会话标题 */
    private String title;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

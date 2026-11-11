package com.rag.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体对话消息实体：助手消息附带工具步骤 JSON，恢复后可重建图表与导出按钮
 */
@Data
public class AgentConversationMessage {

    /** 主键 */
    private Long id;

    /** 所属会话 */
    private Long conversationId;

    /** 角色：user/assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具步骤 JSON（仅助手消息） */
    private String stepsJson;

    /** 创建时间 */
    private LocalDateTime createTime;
}

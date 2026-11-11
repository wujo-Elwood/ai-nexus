package com.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 聊天消息实体类，对应数据库 chat_message 表
 * 存储用户与AI的对话消息记录，按会话分组
 */
@Data
public class ChatMessage {
    /** 消息ID，主键 */
    private Long id;
    /** 所属用户ID */
    private Long userId;
    /** 会话ID，用于标识一次对话 */
    private Long sessionId;
    /** 消息角色：user-用户，assistant-AI助手 */
    private String role;
    /** 消息内容 */
    private String content;
    /** 该消息的反馈状态：1=有帮助，0=无帮助；非数据库字段，查询时由反馈表附带 */
    private Integer helpful;
    /** 创建时间 */
    private LocalDateTime createTime;
}

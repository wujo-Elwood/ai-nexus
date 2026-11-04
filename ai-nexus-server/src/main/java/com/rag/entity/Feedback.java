package com.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 答案质量反馈实体类，对应数据库 ai_feedback 表
 * 记录用户对每条助手回复的有帮助/无帮助评价
 */
@Data
public class Feedback {
    /** 反馈来源：AI 聊天消息 */
    public static final String SOURCE_CHAT = "CHAT";
    /** 反馈来源：智能体会话消息 */
    public static final String SOURCE_AGENT = "AGENT";

    /** 反馈ID，主键 */
    private Long id;
    /** 关联的助手消息ID */
    private Long messageId;
    /** 是否有帮助：1=有帮助，0=无帮助 */
    private Integer helpful;
    /** 反馈来源：CHAT=AI聊天，AGENT=智能体（两类消息表独立自增，需来源才能唯一定位） */
    private String source;
    /** 创建时间 */
    private LocalDateTime createTime;
}

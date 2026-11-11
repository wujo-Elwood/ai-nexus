package com.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 知识库回答质量事实实体，对应 ai_answer_quality 表。 */
@Data
public class AnswerQuality {
    /** 主键。 */
    private Long id;
    /** 助手回答消息编号。 */
    private Long answerMessageId;
    /** 对话会话编号。 */
    private Long sessionId;
    /** 知识库编号。 */
    private Long kbId;
    /** 用户原始问题。 */
    private String question;
    /** 回答置信度，范围 0 到 100。 */
    private Integer confidence;
    /** 证据覆盖率，范围 0 到 100。 */
    private Integer evidenceCoverage;
    /** 是否通过可信回答校验。 */
    private Boolean grounded;
    /** 是否为拒答。 */
    private Boolean refusal;
    /** 拒答或质量原因。 */
    private String reason;
    /** 最新用户反馈：1=有帮助，0=无帮助。 */
    private Integer helpful;
    /** 事实创建时间。 */
    private LocalDateTime createTime;
}

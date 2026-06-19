package com.rag.extract.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽取任务实体类，对应抽取任务表
 */
@Data
public class ExtractTask {

    /**
     * 任务主键
     */
    private Long id;

    /**
     * 文档主键
     */
    private Long documentId;

    /**
     * 模板主键
     */
    private Long templateId;

    /**
     * 任务状态
     */
    private String taskStatus;

    /**
     * 任务消息
     */
    private String taskMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime finishedAt;

    /**
     * 创建用户
     */
    private Long createdBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

package com.rag.task;

import lombok.Data;

import java.time.LocalDateTime;

/** 统一任务中心展示项。 */
@Data
public class TaskCenterItem {
    /** 任务类型 */
    private String taskType;
    /** 业务任务编号 */
    private String taskId;
    /** 展示名称 */
    private String taskName;
    /** 任务状态 */
    private String status;
    /** 任务进度 */
    private Integer progress;
    /** 失败或处理说明 */
    private String message;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 是否允许重试 */
    private Boolean retryable;
    /** 是否允许取消 */
    private Boolean cancellable;
}

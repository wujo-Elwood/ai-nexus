package com.rag.image.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 生图任务实体
 * 保存一次生图请求的执行状态、模型信息和最终结果，便于页面切换后继续查看
 */
@Data
public class ImageTask {

    /** 任务主键 */
    private Long id;

    /** 生图提示词 */
    private String prompt;

    /** 图片尺寸 */
    private String imageSize;

    /** 生成数量 */
    private Integer imageCount;

    /** 任务状态 */
    private String taskStatus;

    /** 任务消息 */
    private String taskMessage;

    /** 任务进度 */
    private Integer progress;

    /** 供应商名称 */
    private String providerName;

    /** 模型名称 */
    private String modelName;

    /** 完整请求参数 JSON */
    private String requestJson;

    /** 生成结果 JSON */
    private String responseJson;

    /** 失败原因 */
    private String errorMessage;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime finishedAt;

    /** 创建用户 */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createTime;
}

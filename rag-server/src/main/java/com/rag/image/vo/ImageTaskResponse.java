package com.rag.image.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 生图任务返回对象
 * 给前端展示任务状态、进度和完成后的图片结果
 */
@Data
@Builder
public class ImageTaskResponse {

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

    /** 失败原因 */
    private String errorMessage;

    /** 生图完成后的结果 */
    private ImageGenerateResponse result;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime finishedAt;
}

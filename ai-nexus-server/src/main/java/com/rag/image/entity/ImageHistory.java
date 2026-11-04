package com.rag.image.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 生图历史记录
 * 保存已生成图片的提示词、模型信息和磁盘文件位置
 */
@Data
public class ImageHistory {

    /** 历史记录ID */
    private Long id;

    /** 生图提示词 */
    private String prompt;

    /** 供应商名称 */
    private String providerName;

    /** 生图模型名称 */
    private String modelName;

    /** 图片尺寸 */
    private String imageSize;

    /** 保存文件名 */
    private String fileName;

    /** 磁盘文件路径 */
    private String filePath;

    /** 图片媒体类型 */
    private String mimeType;

    /** 文件大小 */
    private Long fileSize;

    /** 创建用户 */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createTime;
}

package com.rag.extract.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽取文档实体类，对应抽取文档表
 */
@Data
public class ExtractDocument {

    /**
     * 文档主键
     */
    private Long id;

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 文件类型
     */
    private String fileType;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 解析状态
     */
    private String parseStatus;

    /**
     * 页数
     */
    private Integer pageCount;

    /**
     * 全文内容
     */
    private String fullText;

    /**
     * 上传用户
     */
    private Long uploadedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

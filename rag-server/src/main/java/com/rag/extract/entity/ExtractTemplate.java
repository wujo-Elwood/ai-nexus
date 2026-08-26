package com.rag.extract.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽取模板实体类，对应抽取模板表
 */
@Data
public class ExtractTemplate {

    /**
     * 模板主键
     */
    private Long id;

    /**
     * 模板名称
     */
    private String templateName;

    /**
     * 模板编码
     */
    private String templateCode;

    /**
     * 文档类型
     */
    private String documentType;

    /**
     * 模板说明
     */
    private String description;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 创建用户
     */
    private Long createdBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

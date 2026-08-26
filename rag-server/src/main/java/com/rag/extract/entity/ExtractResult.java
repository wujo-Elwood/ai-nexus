package com.rag.extract.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抽取结果实体类，对应抽取结果表
 */
@Data
public class ExtractResult {

    /**
     * 结果主键
     */
    private Long id;

    /**
     * 任务主键
     */
    private Long taskId;

    /**
     * 文档主键
     */
    private Long documentId;

    /**
     * 字段主键
     */
    private Long fieldId;

    /**
     * 字段编码
     */
    private String fieldCode;

    /**
     * 字段名称
     */
    private String fieldName;

    /**
     * 字段值
     */
    private String fieldValue;

    /**
     * 模型原始值
     */
    private String originalValue;

    /**
     * 人工修正值
     */
    private String manualValue;

    /**
     * 最终值
     */
    private String finalValue;

    /**
     * 原始文本
     */
    private String rawText;

    /**
     * 页码
     */
    private Integer pageNo;

    /**
     * 置信度
     */
    private BigDecimal confidence;

    /**
     * 结果状态
     */
    private String resultStatus;

    /**
     * 是否修改
     */
    private Boolean isModified;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}

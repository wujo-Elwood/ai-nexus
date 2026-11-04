package com.rag.extract.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抽取字段实体类，对应抽取字段表
 */
@Data
public class ExtractField {

    /**
     * 字段主键
     */
    private Long id;

    /**
     * 模板主键
     */
    private Long templateId;

    /**
     * 字段编码
     */
    private String fieldCode;

    /**
     * 字段名称
     */
    private String fieldName;

    /**
     * 字段类型
     */
    private String fieldType;

    /**
     * 是否必填
     */
    private Boolean required;

    /**
     * 是否多值
     */
    private Boolean multiple;

    /**
     * 字段提示词
     */
    private String fieldPrompt;

    /**
     * 示例值
     */
    private String exampleValue;

    /**
     * 正则规则
     */
    private String regexRule;

    /**
     * 置信度阈值
     */
    private BigDecimal confidenceThreshold;

    /**
     * 排序号
     */
    private Integer sortNo;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

package com.rag.extract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 保存抽取模板请求
 */
@Data
public class SaveExtractTemplateRequest {

    /**
     * 模板主键，新增时为空
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
     * 字段列表
     */
    private List<FieldItem> fields;

    /**
     * 模板字段请求项
     */
    @Data
    public static class FieldItem {

        /**
         * 字段主键，新增时为空
         */
        private Long id;

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
    }
}

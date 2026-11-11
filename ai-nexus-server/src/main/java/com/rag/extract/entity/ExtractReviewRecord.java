package com.rag.extract.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽取复核记录实体类，对应抽取复核记录表
 */
@Data
public class ExtractReviewRecord {

    /**
     * 记录主键
     */
    private Long id;

    /**
     * 结果主键
     */
    private Long resultId;

    /**
     * 旧值
     */
    private String oldValue;

    /**
     * 新值
     */
    private String newValue;

    /**
     * 复核用户
     */
    private Long reviewBy;

    /**
     * 复核时间
     */
    private LocalDateTime reviewAt;

    /**
     * 备注
     */
    private String remark;
}

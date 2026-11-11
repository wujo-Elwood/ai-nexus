package com.rag.extract.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽取导出记录实体类，对应抽取导出记录表
 */
@Data
public class ExtractExportRecord {

    /**
     * 记录主键
     */
    private Long id;

    /**
     * 任务主键
     */
    private Long taskId;

    /**
     * 导出类型
     */
    private String exportType;

    /**
     * 导出路径
     */
    private String exportPath;

    /**
     * 导出用户
     */
    private Long exportedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

package com.rag.analysis.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据分析-上传表格实体
 * 上传时解析表结构（Sheet/列/行数）入库，行数据按需从文件读取
 */
@Data
public class AnalysisFile {

    /** 主键 */
    private Long id;

    /** 所属用户 */
    private Long userId;

    /** 原始文件名 */
    private String fileName;

    /** 服务器存储路径 */
    private String filePath;

    /** 表结构 JSON：[{name, columns:[{name,type}], rowCount}] */
    private String sheetsJson;

    /** 总数据行数 */
    private Integer totalRows;

    /** 创建时间 */
    private LocalDateTime createTime;
}

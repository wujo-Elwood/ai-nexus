package com.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识库文件实体类，对应数据库 kb_file 表
 * 记录用户上传到知识库中的文件信息及处理状态
 */
@Data
public class KbFile {
    /** 文件ID，主键 */
    private Long id;
    /** 所属知识库ID，关联 knowledge_base 表 */
    private Long kbId;
    /** 文件名 */
    private String fileName;
    /** 文件类型，如 pdf、txt、docx 等 */
    private String fileType;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 文件存储路径 */
    private String filePath;
    /** 文件版本号 */
    private Integer versionNo;
    /** 同名文件版本组ID */
    private Long versionGroupId;
    /** 父版本文件ID */
    private Long parentVersionId;
    /** 文件 SHA-256 哈希 */
    private String fileSha256;
    /** 所属目录ID */
    private Long folderId;
    /** 文档分类 */
    private String category;
    /** 是否当前版本：1 是，0 否 */
    private Integer isCurrent;
    /** 质量状态 */
    private String qualityStatus;
    /** 质量评分 */
    private java.math.BigDecimal qualityScore;
    /** 向量状态 */
    private String vectorStatus;
    /** 图片文件名和上下文文本 */
    private String imageContext;
    /** 文件处理状态：pending-待处理，processing-处理中，completed-已完成，failed-处理失败 */
    private String status;
    /** 当前处理阶段：已上传、解析、切片、向量化、完成或失败 */
    private String processStage;
    /** 文件处理进度，范围0-100 */
    private Integer progress;
    /** 文件处理失败原因 */
    private String errorMessage;
    /** 文件处理累计尝试次数 */
    private Integer processAttempts;
    /** 下一次自动重试时间 */
    private LocalDateTime nextRetryTime;
    /** 创建时间 */
    private LocalDateTime createTime;
}

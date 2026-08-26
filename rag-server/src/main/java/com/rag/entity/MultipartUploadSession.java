package com.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 大文件分片上传会话实体类，对应 kb_upload_session 表
 */
@Data
public class MultipartUploadSession {
    /** 上传会话编号 */
    private String uploadId;
    /** 所属知识库编号 */
    private Long kbId;
    /** 创建上传会话的用户编号 */
    private Long createUser;
    /** 原始文件名 */
    private String fileName;
    /** 文件 MIME 类型 */
    private String fileType;
    /** 原始文件大小 */
    private Long fileSize;
    /** 完整文件 SHA-256 */
    private String fileSha256;
    /** 单个分片大小 */
    private Long chunkSize;
    /** 分片总数 */
    private Integer totalChunks;
    /** 已上传分片数 */
    private Integer uploadedChunks;
    /** 已上传字节数 */
    private Long uploadedBytes;
    /** 会话状态 */
    private String status;
    /** 失败原因 */
    private String errorMessage;
    /** 会话过期时间 */
    private LocalDateTime expireTime;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}

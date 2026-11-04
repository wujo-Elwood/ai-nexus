package com.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 大文件分片实体类，对应 kb_upload_chunk 表
 */
@Data
public class MultipartUploadChunk {
    /** 上传会话编号 */
    private String uploadId;
    /** 分片序号，从 0 开始 */
    private Integer chunkIndex;
    /** 分片实际大小 */
    private Long chunkSize;
    /** 分片 SHA-256 */
    private String chunkSha256;
    /** 分片临时文件路径 */
    private String filePath;
    /** 创建时间 */
    private LocalDateTime createTime;
}

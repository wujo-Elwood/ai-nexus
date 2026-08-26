package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 初始化大文件分片上传请求
 */
@Data
public class MultipartUploadInitRequest {
    /** 知识库编号 */
    @NotNull
    private Long kbId;
    /** 原始文件名 */
    @NotBlank
    private String fileName;
    /** 文件大小 */
    @NotNull
    private Long fileSize;
    /** 文件 MIME 类型 */
    private String fileType;
    /** 完整文件 SHA-256 */
    private String fileSha256;
}

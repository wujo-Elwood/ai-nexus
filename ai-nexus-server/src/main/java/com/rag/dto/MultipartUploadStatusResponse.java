package com.rag.dto;

import lombok.Data;

import java.util.List;

/**
 * 分片上传进度响应
 */
@Data
public class MultipartUploadStatusResponse {
    /** 上传会话编号 */
    private String uploadId;
    /** 上传状态 */
    private String status;
    /** 分片大小 */
    private Long chunkSize;
    /** 分片总数 */
    private Integer totalChunks;
    /** 已上传字节数 */
    private Long uploadedBytes;
    /** 已上传分片序号 */
    private List<Integer> uploadedChunks;
    /** 失败原因 */
    private String errorMessage;
}

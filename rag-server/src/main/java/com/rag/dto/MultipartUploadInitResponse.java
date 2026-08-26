package com.rag.dto;

import lombok.Data;

import java.util.List;

/**
 * 初始化分片上传响应
 */
@Data
public class MultipartUploadInitResponse {
    /** 上传会话编号 */
    private String uploadId;
    /** 服务端采用的分片大小 */
    private Long chunkSize;
    /** 分片总数 */
    private Integer totalChunks;
    /** 已存在的分片序号 */
    private List<Integer> uploadedChunks;
}

package com.rag.controller;

import com.rag.dto.MultipartUploadInitRequest;
import com.rag.dto.MultipartUploadInitResponse;
import com.rag.dto.MultipartUploadStatusResponse;
import com.rag.service.MultipartUploadService;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 大文件分片上传控制器
 * 提供初始化、分片上传、断点查询、合并和取消接口
 */
@RestController
@RequestMapping("/api/file/multipart")
public class MultipartUploadController {

    @Autowired
    private MultipartUploadService multipartUploadService;

    /**
     * 初始化分片上传会话
     */
    @PostMapping("/init")
    public Result<MultipartUploadInitResponse> init(@Valid @RequestBody MultipartUploadInitRequest request,
                                                    HttpServletRequest httpRequest) {
        Long userId = getUserId(httpRequest);
        return Result.success(multipartUploadService.init(userId, request));
    }

    /**
     * 上传单个文件分片
     */
    @PutMapping(value = "/{uploadId}/chunks/{chunkIndex}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Result<Void> uploadChunk(@PathVariable String uploadId,
                                    @PathVariable int chunkIndex,
                                    HttpServletRequest httpRequest) throws IOException {
        Long userId = getUserId(httpRequest);
        long contentLength = httpRequest.getContentLengthLong();
        String chunkHash = httpRequest.getHeader("X-Chunk-SHA256");
        multipartUploadService.uploadChunk(uploadId, userId, chunkIndex,
                httpRequest.getInputStream(), contentLength, chunkHash);
        return Result.success();
    }

    /**
     * 查询分片上传状态，用于断点恢复
     */
    @GetMapping("/{uploadId}")
    public Result<MultipartUploadStatusResponse> getStatus(@PathVariable String uploadId,
                                                            HttpServletRequest httpRequest) {
        Long userId = getUserId(httpRequest);
        return Result.success(multipartUploadService.getStatus(uploadId, userId));
    }

    /**
     * 合并全部分片并开始知识库文件处理
     */
    @PostMapping("/{uploadId}/complete")
    public Result<com.rag.entity.KbFile> complete(@PathVariable String uploadId,
                                                  @RequestParam(required = false) String fileSha256,
                                                  HttpServletRequest httpRequest) {
        Long userId = getUserId(httpRequest);
        return Result.success(multipartUploadService.complete(uploadId, userId, fileSha256));
    }

    /**
     * 取消分片上传并删除临时分片
     */
    @DeleteMapping("/{uploadId}")
    public Result<Void> cancel(@PathVariable String uploadId, HttpServletRequest httpRequest) {
        Long userId = getUserId(httpRequest);
        multipartUploadService.cancel(uploadId, userId);
        return Result.success();
    }

    /**
     * 从请求属性读取当前用户编号
     */
    private Long getUserId(HttpServletRequest httpRequest) {
        return (Long) httpRequest.getAttribute("userId");
    }
}

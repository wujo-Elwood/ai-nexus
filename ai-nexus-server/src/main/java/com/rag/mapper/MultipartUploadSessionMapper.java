package com.rag.mapper;

import com.rag.entity.MultipartUploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分片上传会话数据访问接口
 */
@Mapper
public interface MultipartUploadSessionMapper {
    /** 根据上传编号查询会话 */
    MultipartUploadSession findByUploadId(@Param("uploadId") String uploadId);

    /** 新增上传会话 */
    int insert(MultipartUploadSession session);

    /** 更新上传进度 */
    int updateProgress(@Param("uploadId") String uploadId,
                       @Param("uploadedChunks") Integer uploadedChunks,
                       @Param("uploadedBytes") Long uploadedBytes);

    /** 更新上传会话状态 */
    int updateStatus(@Param("uploadId") String uploadId,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    /** 仅允许一个请求把上传会话从可合并状态切换为合并中 */
    int markMerging(@Param("uploadId") String uploadId);

    /** 查询已经过期的上传会话 */
    List<MultipartUploadSession> findExpired(@Param("expireTime") LocalDateTime expireTime);

    /** 查询知识库下全部上传会话 */
    List<MultipartUploadSession> findByKbId(@Param("kbId") Long kbId);

    /** 删除上传会话 */
    int deleteByUploadId(@Param("uploadId") String uploadId);

    /** 删除知识库下全部上传会话 */
    int deleteByKbId(@Param("kbId") Long kbId);
}

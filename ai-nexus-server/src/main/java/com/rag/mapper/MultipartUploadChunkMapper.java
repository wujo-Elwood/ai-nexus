package com.rag.mapper;

import com.rag.entity.MultipartUploadChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分片上传数据访问接口
 */
@Mapper
public interface MultipartUploadChunkMapper {
    /** 查询上传会话下的全部分片 */
    List<MultipartUploadChunk> findByUploadId(@Param("uploadId") String uploadId);

    /** 查询指定分片 */
    MultipartUploadChunk findByUploadIdAndIndex(@Param("uploadId") String uploadId,
                                                @Param("chunkIndex") Integer chunkIndex);

    /** 新增分片记录 */
    int insert(MultipartUploadChunk chunk);

    /** 删除指定分片记录 */
    int deleteByUploadIdAndIndex(@Param("uploadId") String uploadId,
                                 @Param("chunkIndex") Integer chunkIndex);

    /** 删除上传会话下的全部分片记录 */
    int deleteByUploadId(@Param("uploadId") String uploadId);

    /** 删除知识库下全部分片记录 */
    int deleteByKbId(@Param("kbId") Long kbId);
}

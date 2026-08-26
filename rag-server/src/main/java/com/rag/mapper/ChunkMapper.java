package com.rag.mapper;

import com.rag.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文本块 Mapper 接口
 * 提供文本块的增删改查和关键词搜索
 */
@Mapper
public interface ChunkMapper {

    /** 根据 ID 查询文本块 */
    KbChunk findById(@Param("id") Long id);

    /** 根据文件 ID 查询所有文本块（按顺序） */
    List<KbChunk> findByFileId(@Param("fileId") Long fileId);

    /** 根据知识库ID查询所有文本块 */
    List<KbChunk> findByKbId(@Param("kbId") Long kbId);

    /** 根据知识库ID统计文本块数量 */
    int countByKbId(@Param("kbId") Long kbId);

    /** 查询当前版本且处理完成的知识库文本块 */
    List<KbChunk> findCompletedCurrentByKbId(@Param("kbId") Long kbId);

    /** 根据 ID 和知识库 ID 查询文本块（通过 kb_file 表关联，确保知识库隔离） */
    KbChunk findByIdAndKbId(@Param("id") Long id, @Param("kbId") Long kbId);

    /** 插入单个文本块 */
    int insert(KbChunk chunk);

    /** 批量插入文本块 */
    int insertBatch(@Param("chunks") List<KbChunk> chunks);

    /** 根据文件 ID 删除所有文本块 */
    int deleteByFileId(@Param("fileId") Long fileId);

    /** 在指定知识库中按关键词模糊搜索文本块（用于混合检索） */
    List<KbChunk> searchByKeyword(@Param("kbId") Long kbId,
                                   @Param("keyword") String keyword,
                                   @Param("limit") int limit);
}

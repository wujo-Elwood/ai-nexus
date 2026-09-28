package com.rag.mapper;

import com.rag.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeBaseMapper {

    /** 根据ID查询知识库 */
    KnowledgeBase findById(@Param("id") Long id);

    /** 查询用户可见的知识库：自己的全部 + 他人的公开的 */
    List<KnowledgeBase> findVisible(@Param("userId") Long userId);

    /** 新增知识库 */
    int insert(KnowledgeBase kb);

    /** 修改知识库基础信息 */
    int update(KnowledgeBase kb);

    /** 删除知识库 */
    int deleteById(@Param("id") Long id);

    /** 统计当前版本文件数 */
    int countCurrentFiles(@Param("kbId") Long kbId);

    /** 统计当前版本文件总大小 */
    long sumCurrentFileBytes(@Param("kbId") Long kbId);

    /** 统计知识库切片数 */
    int countChunks(@Param("kbId") Long kbId);

    /** 统计向量已就绪文件数 */
    int countVectorReadyFiles(@Param("kbId") Long kbId);

    /** 统计当前版本处理失败文件数 */
    int countFailedFiles(@Param("kbId") Long kbId);

    /** 统计重复文件数 */
    int countDuplicateFiles(@Param("kbId") Long kbId);

    /** 统计低质量切片数 */
    int countLowQualityChunks(@Param("kbId") Long kbId);

    /** 查询最近文件 */
    List<Map<String, Object>> findRecentFiles(@Param("kbId") Long kbId, @Param("limit") int limit);

    /** 查询热门文件，当前按切片数量近似热度 */
    List<Map<String, Object>> findPopularFiles(@Param("kbId") Long kbId, @Param("limit") int limit);

    /** 保存知识库摘要和生成时间 */
    int updateSummary(@Param("id") Long id, @Param("summary") String summary);
}

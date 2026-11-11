package com.rag.kb;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库口径冲突 Mapper。
 * 检测采用"全量替换"语义：每次检测先删掉该知识库的旧结果，再批量写入新结果。
 */
@Mapper
public interface KbConflictMapper {

    /** 删除某知识库的全部冲突记录，供重新检测使用 */
    int deleteByKb(@Param("kbId") Long kbId);

    /** 批量写入冲突记录 */
    int insertBatch(@Param("conflicts") List<KbConflict> conflicts);

    /** 按知识库查询冲突，status 为空时返回全部 */
    List<KbConflict> findByKb(@Param("kbId") Long kbId, @Param("status") String status);

    /** 按主键查询 */
    KbConflict findById(@Param("id") Long id);

    /** 更新确认状态 */
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 统计某状态的数量，status 为空表示全部 */
    int countByStatus(@Param("kbId") Long kbId, @Param("status") String status);
}

package com.rag.extract.mapper;

import com.rag.extract.entity.ExtractResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽取结果 Mapper 接口
 */
@Mapper
public interface ExtractResultMapper {

    /**
     * 批量新增抽取结果
     */
    int insertBatch(@Param("results") List<ExtractResult> results);

    /**
     * 根据结果主键查询结果
     */
    ExtractResult findById(@Param("id") Long id);

    /**
     * 根据结果主键和任务创建用户查询结果
     */
    ExtractResult findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 根据任务主键查询结果列表
     */
    List<ExtractResult> findByTaskId(@Param("taskId") Long taskId);

    /**
     * 更新人工修订值
     */
    int updateManualValue(@Param("id") Long id, @Param("fieldValue") String fieldValue, @Param("resultStatus") String resultStatus);
}

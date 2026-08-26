package com.rag.extract.mapper;

import com.rag.extract.entity.ExtractTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 抽取任务 Mapper 接口
 */
@Mapper
public interface ExtractTaskMapper {

    /**
     * 新增抽取任务
     */
    int insert(ExtractTask task);

    /**
     * 根据任务主键查询任务
     */
    ExtractTask findById(@Param("id") Long id);

    /**
     * 根据任务主键和创建用户查询任务
     */
    ExtractTask findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 根据创建用户查询任务列表
     */
    List<ExtractTask> findByUser(@Param("userId") Long userId);

    /**
     * 更新任务状态
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("message") String message);

    /**
     * 更新任务状态和进度
     */
    int updateProgress(@Param("id") Long id, @Param("status") String status, @Param("message") String message, @Param("progress") Integer progress);

    /**
     * 标记任务开始
     */
    int markStarted(@Param("id") Long id, @Param("status") String status);

    /**
     * 标记任务完成
     */
    int markFinished(@Param("id") Long id, @Param("status") String status, @Param("message") String message);

    /**
     * 标记任务失败
     */
    int markFailed(@Param("id") Long id, @Param("message") String message, @Param("errorMessage") String errorMessage);
}

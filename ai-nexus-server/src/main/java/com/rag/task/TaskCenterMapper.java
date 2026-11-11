package com.rag.task;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 统一任务中心查询 Mapper。 */
@Mapper
public interface TaskCenterMapper {
    /** 查询当前用户可见任务 */
    List<TaskCenterItem> findTasks(@Param("userId") Long userId, @Param("status") String status, @Param("taskType") String taskType, @Param("limit") int limit, @Param("offset") int offset);
}

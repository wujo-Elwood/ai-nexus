package com.rag.image.mapper;

import com.rag.image.entity.ImageTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 生图任务 Mapper
 * 提供生图任务创建、状态更新和按用户查询能力
 */
@Mapper
public interface ImageTaskMapper {

    /** 新增生图任务 */
    int insert(ImageTask task);

    /** 抢占用户生图并发名额 */
    int insertIfRunningSlotAvailable(ImageTask task);

    /** 根据任务编号和用户查询任务 */
    ImageTask findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /** 查询用户最近的生图任务 */
    List<ImageTask> findByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);

    /** 统计用户正在执行的生图任务数量 */
    int countRunningByUserId(@Param("userId") Long userId);

    /** 标记任务开始执行 */
    int markStarted(@Param("id") Long id, @Param("providerName") String providerName, @Param("modelName") String modelName);

    /** 更新任务进度和消息 */
    int updateProgress(@Param("id") Long id, @Param("message") String message, @Param("progress") Integer progress);

    /** 标记任务成功 */
    int markSuccess(@Param("id") Long id,
                    @Param("message") String message,
                    @Param("providerName") String providerName,
                    @Param("modelName") String modelName,
                    @Param("responseJson") String responseJson);

    /** 标记任务失败 */
    int markFailed(@Param("id") Long id, @Param("message") String message, @Param("errorMessage") String errorMessage);

    /** 标记服务中断遗留的运行中任务 */
    int markInterruptedTasksFailed(@Param("message") String message, @Param("errorMessage") String errorMessage);
}

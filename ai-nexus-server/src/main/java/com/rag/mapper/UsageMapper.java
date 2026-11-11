package com.rag.mapper;

import com.rag.entity.UsageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 用量日志 Mapper 接口
 * 提供用量记录的写入和统计查询
 */
@Mapper
public interface UsageMapper {

    /** 插入一条用量记录 */
    int insert(UsageLog usageLog);

    /** 查询今日统计：调用次数、总 token 数 */
    Map<String, Object> selectTodayStats();

    /** 查询近 N 天每天的调用次数和 token 数 */
    List<Map<String, Object>> selectDailyStats(@Param("days") int days);
}

package com.rag.service;

import com.rag.entity.UsageLog;
import com.rag.mapper.UsageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计服务
 * 提供用量记录写入和统计数据查询
 */
@Slf4j
@Service
public class UsageService {

    @Autowired
    private UsageMapper usageMapper;

    /**
     * 记录一次 LLM 调用的用量信息
     * 由 ChatService 在每次调用后异步调用，不阻塞主流程
     */
    public void record(UsageLog usageLog) {
        try {
            usageMapper.insert(usageLog);
        } catch (Exception e) {
            log.error("Failed to record usage log", e);
        }
    }

    /**
     * 获取今日用量统计
     * 返回：callCount（调用次数）、totalTokens、promptTokens、completionTokens
     */
    public Map<String, Object> getTodayStats() {
        Map<String, Object> stats = usageMapper.selectTodayStats();
        if (stats == null) {
            stats = new HashMap<>();
            stats.put("callCount", 0);
            stats.put("totalTokens", 0);
            stats.put("promptTokens", 0);
            stats.put("completionTokens", 0);
        }
        return stats;
    }

    /**
     * 获取近 N 天的每日用量趋势
     * 返回列表，每项包含 date、callCount、totalTokens
     */
    public List<Map<String, Object>> getDailyStats(int days) {
        return usageMapper.selectDailyStats(days);
    }
}

package com.rag.controller;

import com.rag.service.UsageService;
import com.rag.vo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计控制器
 * 提供今日统计和近 N 天趋势查询接口
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    @Autowired
    private UsageService usageService;

    /**
     * 获取今日用量统计 + 近 7 天趋势
     * 前端一次请求拿到所有统计数据
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Map<String, Object> result = new HashMap<>();
        // 今日统计
        result.put("today", usageService.getTodayStats());
        // 近 7 天趋势
        result.put("daily", usageService.getDailyStats(7));
        return Result.success(result);
    }
}

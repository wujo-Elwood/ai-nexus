package com.rag.kb;

import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 知识库统计接口，提供统计面板使用的数据。 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseStatsController {

    @Autowired
    private KnowledgeBaseStatsService statsService;

    /** 查询知识库统计概览 */
    @GetMapping("/{kbId}/stats")
    public Result<Map<String, Object>> stats(@PathVariable Long kbId, HttpServletRequest request) {
        // 调用统计服务校验权限并聚合指标
        return Result.success(statsService.getStats(kbId, (Long) request.getAttribute("userId")));
    }
}

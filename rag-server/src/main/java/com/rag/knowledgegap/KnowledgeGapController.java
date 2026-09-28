package com.rag.knowledgegap;

import com.rag.rbac.annotation.RequirePermission;
import com.rag.rbac.annotation.RequireRole;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 知识缺口分析接口，提供报告读取和管理员手动分析。 */
@RestController
@RequestMapping("/api/knowledge-gaps")
public class KnowledgeGapController {
    private final KnowledgeGapAnalysisService analysisService;

    /** 创建知识缺口分析控制器。 */
    public KnowledgeGapController(KnowledgeGapAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** 查询最近 7 天或 30 天的全站知识缺口报告。 */
    @GetMapping("/report")
    @RequirePermission("knowledge-gap:view")
    public Result<Map<String, Object>> report(@RequestParam int days) {
        return Result.success(analysisService.getReport(days));
    }

    /** 管理员手动提交一个窗口的知识缺口分析。 */
    @PostMapping("/analyze")
    @RequirePermission("knowledge-gap:view")
    @RequireRole("admin")
    public Result<Map<String, Object>> analyze(@RequestParam int days, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(analysisService.triggerAnalysis(days, userId));
    }
}

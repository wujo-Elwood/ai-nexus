package com.rag.quality;

import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 知识库健康评分接口。 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeHealthController {
    @Autowired private KnowledgeHealthService healthService;

    /** 返回知识库健康评分和问题统计。 */
    @GetMapping("/{kbId}/health")
    public Result<Map<String,Object>> health(@PathVariable Long kbId, HttpServletRequest request) {
        return Result.success(healthService.calculate(kbId, (Long) request.getAttribute("userId")));
    }
}

package com.rag.kb;

import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库文档口径冲突接口。
 * 权限与文件/目录接口保持一致：查看走 checkAccess，检测与确认走 checkManageAccess。
 */
@RestController
@RequestMapping("/api/kb/{kbId}/conflicts")
public class ConflictController {
    private final ConflictDetectionService conflictDetectionService;

    public ConflictController(ConflictDetectionService conflictDetectionService) {
        this.conflictDetectionService = conflictDetectionService;
    }

    /** 查询冲突列表及各状态数量 */
    @GetMapping
    public Result<Map<String, Object>> list(@PathVariable Long kbId,
                                            @RequestParam(required = false) String status,
                                            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(conflictDetectionService.list(kbId, userId, status));
    }

    /** 触发一次冲突检测，全量替换该知识库的旧结果 */
    @PostMapping("/detect")
    public Result<Map<String, Object>> detect(@PathVariable Long kbId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(conflictDetectionService.detect(kbId, userId));
    }

    /** 确认或忽略一条冲突 */
    @PutMapping("/{conflictId}")
    public Result<KbConflict> update(@PathVariable Long kbId,
                                     @PathVariable Long conflictId,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String status = body == null || body.get("status") == null ? null : String.valueOf(body.get("status"));
        return Result.success(conflictDetectionService.updateStatus(kbId, userId, conflictId, status));
    }
}

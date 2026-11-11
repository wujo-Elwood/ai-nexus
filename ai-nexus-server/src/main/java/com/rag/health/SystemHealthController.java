package com.rag.health;

import com.rag.vo.Result;
import com.rag.rbac.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统健康面板控制器
 * 仅对已登录用户开放，前端入口通过 health:view 菜单权限控制
 */
@RestController
@RequestMapping("/api/system-health")
public class SystemHealthController {

    private final SystemHealthService systemHealthService;
    private final RbacService rbacService;

    /**
     * 创建系统健康控制器
     */
    public SystemHealthController(SystemHealthService systemHealthService) {
        this(systemHealthService, null);
    }

    /**
     * 创建带权限校验的系统健康控制器
     */
    @Autowired
    public SystemHealthController(SystemHealthService systemHealthService, RbacService rbacService) {
        this.systemHealthService = systemHealthService;
        this.rbacService = rbacService;
    }

    /**
     * 查询系统健康概览
     */
    @GetMapping("/overview")
    public Result<SystemHealthResponse> overview(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            return Result.error(401, "请先登录");
        }
        if (rbacService != null && !rbacService.hasPermission(userId, "health:view")) {
            return Result.error(403, "无权限访问系统健康面板");
        }
        return Result.success(systemHealthService.checkOverview());
    }
}

package com.rag.health;

import com.rag.vo.Result;
import com.rag.rbac.service.RbacService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 系统健康控制器测试
 * 验证登录用户能够获取健康概览
 */
class SystemHealthControllerTest {

    /**
     * 测试健康接口返回统一成功结果
     */
    @Test
    void overviewShouldReturnHealthResponseForAuthenticatedUser() {
        SystemHealthService service = mock(SystemHealthService.class);
        RbacService rbacService = mock(RbacService.class);
        SystemHealthResponse response = new SystemHealthResponse();
        when(service.checkOverview()).thenReturn(response);
        when(rbacService.hasPermission(1L, "health:view")).thenReturn(true);
        SystemHealthController controller = new SystemHealthController(service, rbacService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 1L);

        Result<SystemHealthResponse> result = controller.overview(request);

        assertEquals(200, result.getCode());
        assertEquals(response, result.getData());
    }

    /**
     * 测试未登录用户不能读取系统健康数据
     */
    @Test
    void overviewShouldRejectAnonymousUser() {
        SystemHealthService service = mock(SystemHealthService.class);
        SystemHealthController controller = new SystemHealthController(service, mock(RbacService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();

        Result<SystemHealthResponse> result = controller.overview(request);

        assertEquals(401, result.getCode());
        org.junit.jupiter.api.Assertions.assertEquals("请先登录", result.getMessage());
        org.mockito.Mockito.verify(service, never()).checkOverview();
    }

    /**
     * 测试没有 health:view 权限的登录用户不能读取系统健康数据
     */
    @Test
    void overviewShouldRejectUserWithoutHealthPermission() {
        SystemHealthService service = mock(SystemHealthService.class);
        RbacService rbacService = mock(RbacService.class);
        when(rbacService.hasPermission(2L, "health:view")).thenReturn(false);
        SystemHealthController controller = new SystemHealthController(service, rbacService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 2L);

        Result<SystemHealthResponse> result = controller.overview(request);

        assertEquals(403, result.getCode());
        org.junit.jupiter.api.Assertions.assertEquals("无权限访问系统健康面板", result.getMessage());
        org.mockito.Mockito.verify(service, never()).checkOverview();
    }
}

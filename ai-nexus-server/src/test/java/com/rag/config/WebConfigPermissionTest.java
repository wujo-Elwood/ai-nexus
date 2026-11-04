package com.rag.config;

import com.rag.rbac.annotation.RequirePermission;
import com.rag.rbac.annotation.RequireRole;
import com.rag.rbac.service.RbacService;
import com.rag.utils.JwtUtils;
import com.rag.vo.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口级权限校验测试
 * 验证 @RequirePermission 注解的接口统一由认证拦截器做权限编码校验
 */
@WebMvcTest(controllers = {
        WebConfigPermissionTest.SampleController.class,
        WebConfigPermissionTest.ClassLevelController.class,
        WebConfigPermissionTest.RoleController.class
})
@ContextConfiguration(classes = {
        WebConfigPermissionTest.SampleController.class,
        WebConfigPermissionTest.ClassLevelController.class,
        WebConfigPermissionTest.RoleController.class
})
@Import(WebConfig.class)
class WebConfigPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private RbacService rbacService;

    /** 方法级注解样例控制器 */
    @RestController
    static class SampleController {

        @RequirePermission("rbac:manage")
        @GetMapping("/api/test/managed")
        public Result<String> managed() {
            return Result.success("ok");
        }

        @GetMapping("/api/test/open")
        public Result<String> open() {
            return Result.success("ok");
        }
    }

    /** 类级注解样例控制器 */
    @RestController
    @RequirePermission("settings:view")
    static class ClassLevelController {

        @GetMapping("/api/test/providers")
        public Result<String> list() {
            return Result.success("ok");
        }
    }

    /** 角色注解样例控制器。 */
    @RestController
    static class RoleController {

        @RequireRole("admin")
        @GetMapping("/api/test/admin-only")
        public Result<String> adminOnly() {
            return Result.success("ok");
        }
    }

    /** 打桩一个合法登录 Token */
    private void stubValidToken() {
        when(jwtUtils.validateToken("good-token")).thenReturn(true);
        when(jwtUtils.getUserId("good-token")).thenReturn(7L);
        when(jwtUtils.getUsername("good-token")).thenReturn("alice");
    }

    /**
     * 测试拥有权限编码的用户可以访问方法级注解接口
     */
    @Test
    void annotatedEndpointShouldAllowUserWithPermission() throws Exception {
        stubValidToken();
        when(rbacService.hasPermission(7L, "rbac:manage")).thenReturn(true);

        mockMvc.perform(get("/api/test/managed").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    /**
     * 测试缺少权限编码时返回统一的 403 响应体
     */
    @Test
    void annotatedEndpointShouldReturnForbiddenBodyWithoutPermission() throws Exception {
        stubValidToken();
        when(rbacService.hasPermission(7L, "rbac:manage")).thenReturn(false);

        mockMvc.perform(get("/api/test/managed").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限访问该功能，请联系管理员授权"));
    }

    /**
     * 测试未登录访问注解接口仍然返回 401，权限校验不影响认证语义
     */
    @Test
    void annotatedEndpointShouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/test/managed"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 测试无注解接口只要求登录，不做权限编码校验
     */
    @Test
    void openEndpointShouldNotRequirePermission() throws Exception {
        stubValidToken();

        mockMvc.perform(get("/api/test/open").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(rbacService, never()).hasPermission(any(), anyString());
    }

    /**
     * 测试类级注解对该控制器全部接口生效
     */
    @Test
    void classLevelAnnotationShouldProtectAllEndpoints() throws Exception {
        stubValidToken();
        when(rbacService.hasPermission(7L, "settings:view")).thenReturn(false);

        mockMvc.perform(get("/api/test/providers").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        when(rbacService.hasPermission(7L, "settings:view")).thenReturn(true);
        mockMvc.perform(get("/api/test/providers").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 测试角色注解只允许拥有指定启用角色的用户访问。 */
    @Test
    void roleAnnotationShouldRequireAdminRole() throws Exception {
        stubValidToken();
        when(rbacService.hasRole(7L, "admin")).thenReturn(false);

        mockMvc.perform(get("/api/test/admin-only").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        when(rbacService.hasRole(7L, "admin")).thenReturn(true);
        mockMvc.perform(get("/api/test/admin-only").header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}

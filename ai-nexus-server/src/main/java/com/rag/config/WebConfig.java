package com.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.rbac.annotation.RequirePermission;
import com.rag.rbac.annotation.RequireRole;
import com.rag.rbac.service.RbacService;
import com.rag.security.TokenRevocationService;
import com.rag.utils.JwtUtils;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web 配置类
 * 配置跨域（CORS）、JWT 认证拦截器和接口级权限校验
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Token 版本号校验服务：改密或被强制下线后旧 Token 立即失效
     * 声明为非必需，便于只加载 WebConfig 的切片测试不引入数据访问依赖
     */
    @Autowired(required = false)
    private TokenRevocationService tokenRevocationService;

    /** 允许访问后端的前端来源白名单（逗号分隔）；默认仅本机开发地址，部署时通过配置显式追加域名 */
    @Value("${security.cors.allowed-origin-patterns:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOriginPatterns;

    /**
     * 配置跨域策略
     * 允许前端开发服务器（5173/3000 端口）跨域访问所有接口
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(Arrays.stream(allowedOriginPatterns.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 配置请求拦截器
     * 对 /api/** 路径进行 JWT 认证，/api/auth/** 路径（登录/注册）放行
     * 认证通过后再校验接口声明的 @RequirePermission 权限编码
     * 认证通过后将 userId 和 username 写入 request 属性，供 Controller 使用
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                // OPTIONS 预检请求直接放行
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                    return true;
                }

                // 登录/注册接口不需要认证
                String path = request.getRequestURI();
                if (path.startsWith("/api/auth/")) {
                    return true;
                }

                // 解析 Authorization 头中的 JWT Token
                String token = request.getHeader("Authorization");
                if (token != null && token.startsWith("Bearer ")) {
                    token = token.substring(7);
                    if (jwtUtils.validateToken(token)) {
                        // 认证通过，将用户信息写入 request 属性
                        Long userId = jwtUtils.getUserId(token);
                        // 校验令牌版本号：用户改密或管理员强制下线后，旧 Token 立即失效
                        if (tokenRevocationService != null
                                && !tokenRevocationService.isTokenStillValid(userId, jwtUtils.getTokenVersion(token))) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            return false;
                        }
                        request.setAttribute("userId", userId);
                        request.setAttribute("username", jwtUtils.getUsername(token));
                        // 认证之后校验接口声明的权限编码
                        return enforceAccessRequirements(handler, userId, response);
                    }
                }

                // 认证失败，返回 401
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        }).addPathPatterns("/api/**");
    }

    /**
     * 校验接口声明的 @RequirePermission 权限编码
     * 方法上没有注解时回退读取类上的注解；没有注解的接口只要登录即可访问
     * 拒绝时按项目统一约定返回 HTTP 200 + Result{code:403}，前端据此提示错误信息
     */
    /** 校验接口声明的角色和权限要求。 */
    boolean enforceAccessRequirements(Object handler, Long userId, HttpServletResponse response) throws java.io.IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole roleRequirement = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (roleRequirement == null) {
            roleRequirement = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (roleRequirement != null && !rbacService.hasRole(userId, roleRequirement.value())) {
            writeForbidden(response);
            return false;
        }
        RequirePermission requirement = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (requirement == null) {
            requirement = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (requirement == null) {
            return true;
        }
        if (rbacService.hasPermission(userId, requirement.value())) {
            return true;
        }
        writeForbidden(response);
        return false;
    }

    /** 写入项目统一的无权限响应。 */
    private void writeForbidden(HttpServletResponse response) throws java.io.IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(403, "无权限访问该功能，请联系管理员授权")));
    }
}

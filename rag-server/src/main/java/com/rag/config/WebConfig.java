package com.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.rbac.annotation.RequirePermission;
import com.rag.rbac.annotation.RequireRole;
import com.rag.rbac.service.RbacService;
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

    /** 允许访问后端的前端来源规则，支持本机域名、回环地址和实际 IP */
    @Value("${security.cors.allowed-origin-patterns:http://*:5173}")
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

                // 图片新标签页查看无法携带 Authorization 头，允许通过 URL token 认证
                String queryToken = request.getParameter("token");
                if (isImageFileAccess(path) && queryToken != null && jwtUtils.validateToken(queryToken)) {
                    request.setAttribute("userId", jwtUtils.getUserId(queryToken));
                    request.setAttribute("username", jwtUtils.getUsername(queryToken));
                    return true;
                }

                // 解析 Authorization 头中的 JWT Token
                String token = request.getHeader("Authorization");
                if (token != null && token.startsWith("Bearer ")) {
                    token = token.substring(7);
                    if (jwtUtils.validateToken(token)) {
                        // 认证通过，将用户信息写入 request 属性
                        Long userId = jwtUtils.getUserId(token);
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

            /**
             * 判断是否为图片文件访问接口
             */
            private boolean isImageFileAccess(String path) {
                // 第1步：只允许生图历史的查看和下载接口走 URL token
                return path.matches("/api/image/history/\\d+/(view|download)");
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

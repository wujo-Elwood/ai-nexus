package com.rag.config;

import com.rag.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web 配置类
 * 配置跨域（CORS）和 JWT 认证拦截器
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtUtils jwtUtils;

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
     * 认证通过后将 userId 和 username 写入 request 属性，供 Controller 使用
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
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
                        request.setAttribute("userId", jwtUtils.getUserId(token));
                        request.setAttribute("username", jwtUtils.getUsername(token));
                        return true;
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
}

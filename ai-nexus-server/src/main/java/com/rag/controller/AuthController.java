package com.rag.controller;

import com.rag.dto.LoginRequest;
import com.rag.dto.RegisterRequest;
import com.rag.service.UserService;
import com.rag.vo.LoginResponse;
import com.rag.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * 提供用户注册和登录接口，返回 JWT Token
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    /**
     * 用户注册
     * 创建新用户并返回 JWT Token
     */
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        LoginResponse response = userService.register(request);
        return Result.success(response);
    }

    /**
     * 用户登录
     * 验证用户名密码并返回 JWT Token
     * 客户端 IP 参与登录失败限流，避免脚本反复撞库
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        LoginResponse response = userService.login(request, clientIp(servletRequest));
        return Result.success(response);
    }

    /**
     * 取客户端真实 IP
     * 生产环境经 Nginx 反向代理时 getRemoteAddr 只会拿到代理地址，
     * 因此优先读 X-Forwarded-For 的第一个地址；取不到时退回连接地址
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}

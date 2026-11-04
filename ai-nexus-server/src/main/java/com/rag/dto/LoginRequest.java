package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户登录请求 DTO
 * 用于接收前端传递的登录参数
 */
@Data
public class LoginRequest {
    /** 用户名，必填 */
    @NotBlank(message = "Username is required")
    private String username;

    /** 密码，必填 */
    @NotBlank(message = "Password is required")
    private String password;
}

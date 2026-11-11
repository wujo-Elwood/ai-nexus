package com.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户注册请求 DTO
 * 用于接收前端传递的注册参数
 */
@Data
public class RegisterRequest {
    /** 用户名，必填，长度3-50个字符 */
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    private String username;

    /** 密码，必填，至少6个字符 */
    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    /** 昵称，选填 */
    private String nickname;
}

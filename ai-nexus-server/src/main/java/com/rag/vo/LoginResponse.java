package com.rag.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录响应 VO
 * 返回登录成功后的用户信息和认证令牌
 */
@Data
@Builder
public class LoginResponse {
    /** JWT 认证令牌，后续请求需携带此 token */
    private String token;
    /** 用户ID */
    private Long userId;
    /** 用户名 */
    private String username;
    /** 昵称 */
    private String nickname;
}

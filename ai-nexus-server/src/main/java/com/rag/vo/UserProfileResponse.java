package com.rag.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户资料响应
 * 返回当前登录用户可以在页面查看和修改的基础信息
 */
@Data
@Builder
public class UserProfileResponse {

    /** 用户ID */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatar;

    /** 吉祥物外观偏好 JSON（skin/outfit/name），未设置时为 null */
    private String mascotPrefs;

    /** 创建时间 */
    private LocalDateTime createTime;
}

package com.rag.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统用户实体类，对应数据库 sys_user 表
 * 存储用户的基本信息，用于用户注册、登录认证等场景
 */
@Data
public class SysUser {
    /** 用户ID，主键 */
    private Long id;
    /** 用户名，用于登录 */
    private String username;
    /** 密码 */
    private String password;
    /** 昵称，用于界面展示 */
    private String nickname;
    /** 头像URL */
    private String avatar;
    /** 吉祥物外观偏好 JSON（skin/outfit/name），跟随账号跨浏览器保留 */
    private String mascotPrefs;
    /** 令牌版本号：改密或强制下线时递增，使已签发的旧 Token 立即失效 */
    private Integer tokenVersion;
    /** 创建时间 */
    private LocalDateTime createTime;
}

package com.rag.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库共享授权实体
 * 把私有知识库授权给指定用户（USER）或角色/组（ROLE），READ 可读、MANAGE 可管理
 */
@Data
public class KbGrant {

    /** 授权对象类型：指定用户 */
    public static final String TYPE_USER = "USER";
    /** 授权对象类型：角色/组 */
    public static final String TYPE_ROLE = "ROLE";
    /** 权限级别：可阅读 */
    public static final String PERM_READ = "READ";
    /** 权限级别：可管理（含 READ 全部能力） */
    public static final String PERM_MANAGE = "MANAGE";

    /** 主键 */
    private Long id;

    /** 知识库编号 */
    private Long kbId;

    /** 授权对象类型：USER/ROLE */
    private String granteeType;

    /** 授权对象编号：用户编号或角色编号 */
    private Long granteeId;

    /** 权限级别：READ/MANAGE */
    private String permission;

    /** 授权操作人（知识库创建者） */
    private Long createUser;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 授权对象名称（仅查询展示用：用户昵称/用户名或角色名） */
    private String granteeName;
}

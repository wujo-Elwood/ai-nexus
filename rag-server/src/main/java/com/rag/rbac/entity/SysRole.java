package com.rag.rbac.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统角色实体
 * 对应 sys_role 表，用于承载一组菜单权限
 */
@Data
public class SysRole {
    /** 角色编号 */
    private Long id;
    /** 角色名称 */
    private String roleName;
    /** 角色编码 */
    private String roleCode;
    /** 角色说明 */
    private String description;
    /** 是否启用：1=启用，0=停用 */
    private Integer enabled;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}

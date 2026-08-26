package com.rag.rbac.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统菜单实体
 * 对应 sys_menu 表，用于控制平台左侧菜单和页面访问入口
 */
@Data
public class SysMenu {
    /** 菜单编号 */
    private Long id;
    /** 父级菜单编号，0 表示根节点 */
    private Long parentId;
    /** 菜单名称 */
    private String menuName;
    /** 前端路由地址 */
    private String path;
    /** 前端路由名称 */
    private String routeName;
    /** 前端组件路径 */
    private String component;
    /** 菜单图标或符号 */
    private String icon;
    /** 菜单类型：DIR=目录，MENU=菜单 */
    private String menuType;
    /** 权限标识 */
    private String permissionCode;
    /** 排序号 */
    private Integer sortNo;
    /** 是否可见：1=显示，0=隐藏 */
    private Integer visible;
    /** 是否启用：1=启用，0=停用 */
    private Integer enabled;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;
}

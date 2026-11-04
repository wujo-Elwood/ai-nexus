package com.rag.rbac.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点
 * 用于返回给前端渲染侧边栏和菜单管理树
 */
@Data
public class MenuTreeNode {
    /** 菜单编号 */
    private Long id;
    /** 父级菜单编号 */
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
    /** 菜单类型 */
    private String menuType;
    /** 权限标识 */
    private String permissionCode;
    /** 排序号 */
    private Integer sortNo;
    /** 是否可见 */
    private Integer visible;
    /** 是否启用 */
    private Integer enabled;
    /** 子菜单 */
    private List<MenuTreeNode> children = new ArrayList<>();
}

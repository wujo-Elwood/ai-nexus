package com.rag.rbac.mapper;

import com.rag.rbac.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统菜单 Mapper
 * 负责菜单基础数据和用户可见菜单查询
 */
@Mapper
public interface SysMenuMapper {

    /** 查询当前用户可见菜单 */
    List<SysMenu> findByUserId(@Param("userId") Long userId);

    /** 查询全部菜单 */
    List<SysMenu> findAll();

    /** 根据编号查询菜单 */
    SysMenu findById(@Param("id") Long id);

    /** 新增菜单 */
    int insert(SysMenu menu);

    /** 修改菜单 */
    int update(SysMenu menu);

    /** 删除菜单 */
    int deleteById(@Param("id") Long id);

    /** 删除菜单对应的角色授权 */
    int deleteRoleMenuByMenuId(@Param("menuId") Long menuId);
}

package com.rag.rbac.mapper;

import com.rag.rbac.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统角色 Mapper
 * 负责角色、用户角色和角色菜单授权数据访问
 */
@Mapper
public interface SysRoleMapper {

    /** 查询全部角色 */
    List<SysRole> findAll();

    /** 查询用户拥有的角色 */
    List<SysRole> findByUserId(@Param("userId") Long userId);

    /** 根据编号查询角色 */
    SysRole findById(@Param("id") Long id);

    /** 新增角色 */
    int insert(SysRole role);

    /** 修改角色 */
    int update(SysRole role);

    /** 删除角色 */
    int deleteById(@Param("id") Long id);

    /** 查询角色菜单编号 */
    List<Long> findMenuIdsByRoleId(@Param("roleId") Long roleId);

    /** 删除角色菜单授权 */
    int deleteRoleMenus(@Param("roleId") Long roleId);

    /** 新增角色菜单授权 */
    int insertRoleMenu(@Param("roleId") Long roleId, @Param("menuId") Long menuId);

    /** 删除用户角色授权 */
    int deleteUserRoles(@Param("userId") Long userId);

    /** 新增用户角色授权 */
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /** 删除指定角色下的用户关联 */
    int deleteUserRoleByRoleId(@Param("roleId") Long roleId);

    /** 删除指定角色下的菜单关联 */
    int deleteRoleMenuByRoleId(@Param("roleId") Long roleId);
}

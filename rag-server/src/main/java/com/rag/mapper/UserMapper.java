package com.rag.mapper;

import com.rag.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 Mapper 接口
 * 提供用户的增查改操作
 */
@Mapper
public interface UserMapper {

    /** 根据用户ID查询用户信息 */
    SysUser findById(@Param("id") Long id);

    /** 根据用户名查询用户信息（登录时使用） */
    SysUser findByUsername(@Param("username") String username);

    /** 查询全部用户信息（不返回密码） */
    List<SysUser> findAll();

    /** 新增用户 */
    int insert(SysUser user);

    /** 修改用户资料 */
    int updateProfile(SysUser user);

    /** 修改用户密码 */
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /** 删除用户角色授权 */
    int deleteUserRoles(@Param("id") Long id);

    /** 删除用户 */
    int deleteById(@Param("id") Long id);
}

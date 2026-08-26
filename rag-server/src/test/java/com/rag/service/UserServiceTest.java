package com.rag.service;

import com.rag.common.BusinessException;
import com.rag.entity.SysUser;
import com.rag.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户服务测试
 * 验证用户管理里的删除行为
 */
class UserServiceTest {

    /**
     * 测试删除用户时会先清理用户角色再删除用户本身
     */
    @Test
    void deleteUserShouldRemoveRolesBeforeDeletingUser() {
        // 第1步：准备一个待删除用户和内存版 Mapper
        MemoryUserMapper userMapper = new MemoryUserMapper();
        userMapper.users.add(buildUser(20L, "zhangsan"));
        UserService userService = new UserService();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);

        // 第2步：删除非当前登录用户
        userService.deleteUser(20L, 1L);

        // 第3步：确认先清理角色，再删除用户
        assertEquals(List.of("deleteRoles:20", "deleteUser:20"), userMapper.operations);
    }

    /**
     * 测试不能删除当前登录用户
     */
    @Test
    void deleteUserShouldRejectCurrentUser() {
        // 第1步：准备当前登录用户
        MemoryUserMapper userMapper = new MemoryUserMapper();
        userMapper.users.add(buildUser(20L, "zhangsan"));
        UserService userService = new UserService();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);

        // 第2步：尝试删除当前用户
        BusinessException exception = assertThrows(BusinessException.class, () -> userService.deleteUser(20L, 20L));

        // 第3步：确认服务拒绝删除自己
        assertEquals("不能删除当前登录用户", exception.getMessage());
        assertEquals(List.of(), userMapper.operations);
    }

    /**
     * 测试不能删除 admin 管理员账号
     */
    @Test
    void deleteUserShouldRejectAdminUser() {
        // 第1步：准备 admin 用户
        MemoryUserMapper userMapper = new MemoryUserMapper();
        userMapper.users.add(buildUser(2L, "admin"));
        UserService userService = new UserService();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);

        // 第2步：尝试删除 admin 用户
        BusinessException exception = assertThrows(BusinessException.class, () -> userService.deleteUser(2L, 1L));

        // 第3步：确认服务拒绝删除管理员账号
        assertEquals("不能删除 admin 管理员账号", exception.getMessage());
        assertEquals(List.of(), userMapper.operations);
    }

    /**
     * 构建测试用户
     */
    private SysUser buildUser(Long id, String username) {
        // 第1步：填充删除逻辑需要的用户字段
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        return user;
    }

    /**
     * 内存版用户 Mapper
     */
    private static class MemoryUserMapper implements UserMapper {
        private final List<SysUser> users = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();

        /** 根据用户ID查询用户信息 */
        @Override
        public SysUser findById(Long id) {
            return users.stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }

        /** 根据用户名查询用户信息 */
        @Override
        public SysUser findByUsername(String username) {
            return users.stream().filter(item -> item.getUsername().equals(username)).findFirst().orElse(null);
        }

        /** 查询全部用户信息 */
        @Override
        public List<SysUser> findAll() {
            return users;
        }

        /** 新增用户 */
        @Override
        public int insert(SysUser user) {
            users.add(user);
            return 1;
        }

        /** 修改用户资料 */
        @Override
        public int updateProfile(SysUser user) {
            return 1;
        }

        /** 修改用户密码 */
        @Override
        public int updatePassword(Long id, String password) {
            return 1;
        }

        /** 删除用户角色授权 */
        @Override
        public int deleteUserRoles(Long id) {
            operations.add("deleteRoles:" + id);
            return 1;
        }

        /** 删除用户 */
        @Override
        public int deleteById(Long id) {
            operations.add("deleteUser:" + id);
            return 1;
        }
    }
}

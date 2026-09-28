package com.rag.rbac.service;

import com.rag.rbac.entity.SysMenu;
import com.rag.rbac.entity.SysRole;
import com.rag.rbac.mapper.SysMenuMapper;
import com.rag.rbac.mapper.SysRoleMapper;
import com.rag.rbac.vo.MenuTreeNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC 菜单服务测试
 * 验证用户菜单树和角色菜单授权的核心行为
 */
class RbacServiceTest {

    /**
     * 测试当前用户菜单会按父子关系和排序号组成树
     */
    @Test
    void listCurrentUserMenusShouldBuildSortedMenuTree() {
        // 第1步：准备用户可见菜单数据，顺序故意打乱
        MemorySysMenuMapper menuMapper = new MemorySysMenuMapper();
        menuMapper.userMenus.add(buildMenu(3L, 1L, "AI 聊天", "/chat", "◇", 20));
        menuMapper.userMenus.add(buildMenu(2L, 1L, "知识库", "/kb", "▣", 10));
        menuMapper.userMenus.add(buildMenu(1L, 0L, "功能", "", "", 1));
        RbacService service = new RbacService(menuMapper, new MemorySysRoleMapper());

        // 第2步：读取当前用户菜单树
        List<MenuTreeNode> menus = service.listCurrentUserMenus(100L);

        // 第3步：确认父级和子级都按排序号稳定输出
        assertEquals(1, menus.size());
        assertEquals("功能", menus.get(0).getMenuName());
        assertEquals(2, menus.get(0).getChildren().size());
        assertEquals("知识库", menus.get(0).getChildren().get(0).getMenuName());
        assertEquals("AI 聊天", menus.get(0).getChildren().get(1).getMenuName());
    }

    /**
     * 测试只有启用的管理员角色可以执行全局向量重建
     */
    @Test
    void hasRoleShouldRequireEnabledRoleCode() {
        MemorySysRoleMapper roleMapper = new MemorySysRoleMapper();
        SysRole admin = new SysRole();
        admin.setRoleCode("admin");
        admin.setEnabled(1);
        roleMapper.roles.add(admin);
        RbacService service = new RbacService(new MemorySysMenuMapper(), roleMapper);

        assertTrue(service.hasRole(100L, "admin"));
        admin.setEnabled(0);
        assertFalse(service.hasRole(100L, "admin"));
    }

    /**
     * 测试接口级权限校验按菜单权限编码匹配
     */
    @Test
    void hasPermissionShouldMatchUserMenuPermissionCode() {
        // 第1步：给用户授予带 rbac:manage 权限码的菜单
        MemorySysMenuMapper menuMapper = new MemorySysMenuMapper();
        SysMenu managed = buildMenu(9L, 0L, "权限管理", "/rbac", "⚙", 90);
        managed.setPermissionCode("rbac:manage");
        menuMapper.userMenus.add(managed);
        RbacService service = new RbacService(menuMapper, new MemorySysRoleMapper());

        // 第2步：拥有对应权限码时放行，其他权限码拒绝
        assertTrue(service.hasPermission(100L, "rbac:manage"));
        assertFalse(service.hasPermission(100L, "settings:view"));
        assertFalse(service.hasPermission(null, "rbac:manage"));
    }

    /**
     * 构建测试菜单
     */
    private SysMenu buildMenu(Long id, Long parentId, String menuName, String path, String icon, Integer sortNo) {
        // 第1步：填充菜单树需要的核心字段
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuName(menuName);
        menu.setPath(path);
        menu.setIcon(icon);
        menu.setSortNo(sortNo);
        menu.setVisible(1);
        menu.setEnabled(1);
        return menu;
    }

    /**
     * 内存版菜单 Mapper
     */
    private static class MemorySysMenuMapper implements SysMenuMapper {
        private final List<SysMenu> userMenus = new ArrayList<>();

        /** 查询当前用户可见菜单 */
        @Override
        public List<SysMenu> findByUserId(Long userId) {
            return userMenus;
        }

        /** 查询所有菜单 */
        @Override
        public List<SysMenu> findAll() {
            return userMenus;
        }

        /** 查询菜单详情 */
        @Override
        public SysMenu findById(Long id) {
            return userMenus.stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }

        /** 新增菜单 */
        @Override
        public int insert(SysMenu menu) {
            userMenus.add(menu);
            return 1;
        }

        /** 修改菜单 */
        @Override
        public int update(SysMenu menu) {
            return 1;
        }

        /** 删除菜单 */
        @Override
        public int deleteById(Long id) {
            return 1;
        }

        /** 删除角色菜单关联 */
        @Override
        public int deleteRoleMenuByMenuId(Long menuId) {
            return 1;
        }
    }

    /**
     * 内存版角色 Mapper
     */
    private static class MemorySysRoleMapper implements SysRoleMapper {
        private final List<SysRole> roles = new ArrayList<>();
        private final List<Long> menuIds = new ArrayList<>();

        /** 查询全部角色 */
        @Override
        public List<SysRole> findAll() {
            return roles;
        }

        /** 查询用户拥有的角色 */
        @Override
        public List<SysRole> findByUserId(Long userId) {
            return roles;
        }

        /** 查询角色详情 */
        @Override
        public SysRole findById(Long id) {
            return roles.stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }

        /** 新增角色 */
        @Override
        public int insert(SysRole role) {
            roles.add(role);
            return 1;
        }

        /** 修改角色 */
        @Override
        public int update(SysRole role) {
            return 1;
        }

        /** 删除角色 */
        @Override
        public int deleteById(Long id) {
            return 1;
        }

        /** 查询角色菜单编号 */
        @Override
        public List<Long> findMenuIdsByRoleId(Long roleId) {
            return menuIds;
        }

        /** 删除角色菜单授权 */
        @Override
        public int deleteRoleMenus(Long roleId) {
            menuIds.clear();
            return 1;
        }

        /** 新增角色菜单授权 */
        @Override
        public int insertRoleMenu(Long roleId, Long menuId) {
            menuIds.add(menuId);
            return 1;
        }

        /** 删除用户角色授权 */
        @Override
        public int deleteUserRoles(Long userId) {
            return 1;
        }

        /** 新增用户角色授权 */
        @Override
        public int insertUserRole(Long userId, Long roleId) {
            return 1;
        }

        /** 删除指定角色下的用户关联 */
        @Override
        public int deleteUserRoleByRoleId(Long roleId) {
            return 1;
        }

        /** 删除指定角色下的菜单关联 */
        @Override
        public int deleteRoleMenuByRoleId(Long roleId) {
            return 1;
        }
    }
}
